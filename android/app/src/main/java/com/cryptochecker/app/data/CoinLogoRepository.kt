package com.cryptochecker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.util.LruCache
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.NetworkError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Coin-Logos, Namen und Alpha-Kennzeichen (Regeln in [CoinLogos], [CoinMeta], [CoinLists]). Nichts
 * wird einzeln geholt — so erfährt niemand, welche Coins in einer Merkliste stehen:
 *
 * 1. Zuerst und gleichzeitig Binance-Symbolliste und Alpha-Liste ([refreshLists]): Namen und
 *    Alpha-Kennzeichen sind wenige Sekunden nach dem Start da — unabhängig von CoinGecko und den
 *    Bildern. Fehlt eine direkt, kommt sie aus `coin_meta.json` (Release «data», nur Binance-Daten).
 *    Vor der ersten Liste gelten gespeicherte Namen bzw. der mitgelieferte Auszug
 *    ([CoinMeta.SNAPSHOT_FILE]).
 * 2. Rangliste von CoinGecko (Rang 1–2500, höchstens einmal pro Tag — CoinGecko: höchstens 24 h
 *    zwischenspeichern), zusammengeführt mit den Listen aus 1 ([CoinMeta.merge]). Fehlte eine Quelle,
 *    bleibt Bekanntes erhalten und der Abruf wird nach einigen Stunden wiederholt.
 * 3. [startSync] lädt die Logos **aller** Coins dieser Liste von den Bildservern von CoinGecko und
 *    Binance, mehrere gleichzeitig je Server ([PER_HOST_PARALLEL]); die Bilder der grössten 1000
 *    beginnen schon, während die übrigen Seiten der Rangliste noch laden. Später fehlen nur neu
 *    dazugekommene oder geänderte. Bremst ein Bild-Server (429/403/503), pausiert nur dieser.
 * 4. Angezeigt wird nur aus Speicher und Datei ([logo]); fehlt ein geladenes Logo, das
 *    mitgelieferte Icon-Paket ([CoinLogos.ICON_PACK_FILE], CC0), sonst Initialen.
 *
 * Alle drei Schalter «Coin-Logos» aus → [startSync] lädt keine Bilder.
 */
@Singleton
class CoinLogoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
    private val connectivity: ConnectivityMonitor,
    private val tokenIdentityStore: TokenIdentityStore,
) {
    /** Gleiche Verbindungen, aber ohne Mitschnitt (sonst landen Bild-Bytes im Debug-Protokoll). */
    private val client: OkHttpClient = httpClient.newBuilder().apply { interceptors().clear() }.build()
    private val apiClient: OkHttpClient = httpClient

    /**
     * Binance- und Alpha-Liste (je mehrere MB): längere Zeitgrenze wie bei den Paarlisten — mit den
     * 20 s für Kurse brach die Alpha-Liste bei langsamem Netz ab und die Alpha-Kürzel kamen nie an.
     */
    private val listClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(LIST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(LIST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mapFile by lazy { File(context.noBackupFilesDir, MAP_FILE) }
    private val namesFile by lazy { File(context.noBackupFilesDir, NAMES_FILE) }
    private val imageDir by lazy { File(context.cacheDir, IMAGE_DIR).apply { mkdirs() } }

    private val mapMutex = Mutex()
    @Volatile private var map: Map<String, String>? = null

    /** Zuletzt benutzte Bilder, höchstens ~4 MB. */
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val downloads = Semaphore(MAX_PARALLEL_DOWNLOADS)
    /** Gleichzeitige Bild-Abrufe je Server ([PER_HOST_PARALLEL]). */
    private val hostPermits = ConcurrentHashMap<String, Semaphore>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Any()
    private var syncJob: Job? = null

    private val _revision = MutableStateFlow(0)

    /**
     * Letzter Abgleich scheiterte am fehlenden Netz (z. B. kein Internet direkt nach der Installation):
     * [CoinLogoSync] holt ihn nach, sobald wieder Netz da ist ([CoinLogoRetry.syncNeeded]). Nur im
     * Speicher — nach einem Neustart der App läuft der Abgleich ohnehin beim Öffnen.
     */
    private val _retryPending = MutableStateFlow(false)
    val retryPending: StateFlow<Boolean> = _retryPending.asStateFlow()

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Namen der Coins und TradFi-Paare («BTC» → «Bitcoin», «TRADFI:NVDA» → «NVIDIA»), aus denselben
     * Listen wie die Logos (Schlüssel [CoinLogos.nameKey]); leer, bis die Liste geladen ist.
     */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Name für ein Paar der Merkliste (TradFi beachtet); null = keiner bekannt. */
    fun name(marketKey: String, base: String, quote: String, contractType: String): String? =
        _names.value[CoinLogos.nameKey(base, isTradFi(marketKey, base, quote, contractType))]

    /** Steigt, sobald neue Logos auf dem Gerät liegen (Anzeigen laden dann neu). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tradFiPairs = MutableStateFlow(loadTradFiPairs())

    /**
     * Paare der Merkliste, die TradFi sind (Kennungen [CoinLogos.pairKey]); ihr Logo kommt nur aus
     * dem TradFi-Namensraum ([CoinLogos.tradFiKey]). Gespeichert, damit auch Widgets es wissen.
     */
    val tradFiPairs: StateFlow<Set<String>> = _tradFiPairs.asStateFlow()

    fun isTradFi(marketKey: String, base: String, quote: String, contractType: String): Boolean =
        CoinLogos.pairKey(marketKey, base, quote, contractType) in _tradFiPairs.value

    /** Schlüssel für die Plakette eines Paars (TradFi oder Krypto). */
    fun logoKey(marketKey: String, base: String, quote: String, contractType: String): String =
        CoinLogos.logoKey(base, isTradFi(marketKey, base, quote, contractType))

    /** Neue Liste der TradFi-Paare; true, wenn sie sich geändert hat. */
    fun setTradFiPairs(pairs: Set<String>): Boolean {
        if (pairs == _tradFiPairs.value) return false
        prefs.edit { putStringSet(KEY_TRADFI_PAIRS, pairs) }
        _tradFiPairs.value = pairs
        return true
    }

    private val _alphaSymbols = MutableStateFlow(loadAlphaSymbols())

    /**
     * Kürzel der Alpha-Token ([CoinLogos.alphaSymbols]): Binance-Alpha-Liste ohne regulär an
     * Binance Spot gelistete. Kommt als Erstes im Abgleich ([refreshLists]) und ist gespeichert; bis
     * dahin aus dem mitgelieferten Auszug ([CoinMeta.SNAPSHOT_FILE]). Für das Symbol «α»
     * ([com.cryptochecker.app.domain.watch.AssetKind]) und «inkl. Alpha-Tokens».
     */
    val alphaSymbols: StateFlow<Set<String>> = _alphaSymbols.asStateFlow()

    /** Neue Alpha-Liste speichern (auch leer: «schon einmal geholt»). */
    private fun storeAlphaSymbols(symbols: Set<String>) {
        prefs.edit { putStringSet(KEY_ALPHA_SYMBOLS, symbols) }
        _alphaSymbols.value = symbols
    }

    /** Frisch geholte Alpha-Liste speichern, mit Zeitpunkt (Frische für [refreshAlphaOnly]). */
    private fun storeAlphaFetched(symbols: Set<String>, now: Long) {
        storeAlphaSymbols(symbols)
        prefs.edit { putLong(KEY_ALPHA_TIME, now) }
    }

    private val _alphaTokens = MutableStateFlow<List<BinanceAlpha.Token>>(emptyList())

    /**
     * Token der Alpha-Liste für die Alpha-Paare von Binance ([BinanceAlpha.pairs], Kennung `alphaId`) und
     * «gleicher Token»: aus den gespeicherten Listen ([loadAlphaTokens] lädt sie einmal), nach jeder neuen
     * Alpha-Liste ersetzt. Leer, bis geladen.
     */
    val alphaTokens: StateFlow<List<BinanceAlpha.Token>> = _alphaTokens.asStateFlow()

    private val _alphaNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Namen der Alpha-Token je Kürzel ([CoinLogos.alphaNames]) — weniger Fehltreffer bei «α» ([com.cryptochecker.app.domain.watch.AssetKind]). */
    val alphaNames: StateFlow<Map<String, String>> = _alphaNames.asStateFlow()

    private fun publishAlphaTokens(entries: List<CoinLogos.AlphaEntry>) {
        val tokens = CoinLogos.alphaTokens(entries)
        _alphaTokens.value = tokens
        _alphaNames.value = CoinLogos.alphaNames(tokens)
    }

    /** Gespeicherte Alpha-Token (nie Netz); liest die gespeicherten Listen beim ersten Aufruf. */
    suspend fun loadAlphaTokens(): List<BinanceAlpha.Token> {
        if (_alphaTokens.value.isEmpty()) savedLists()?.alpha?.takeIf { it.isNotEmpty() }?.let { publishAlphaTokens(it) }
        return _alphaTokens.value
    }

    /**
     * Nur die Alpha-Liste holen ([CoinLogos.alphaOnly]: Logos und Namen aus, «inkl. Alpha-Tokens» an) —
     * ohne Rangliste und ohne Bilder, für alle gleich; höchstens einmal pro Tag ([refreshLists]).
     */
    suspend fun refreshAlphaOnly() {
        refreshLists(includeBinance = false)
    }

    // ---- 1. Binance-Symbolliste und Alpha-Liste (Namen, Alpha-Kennzeichen) — vor allem anderen

    private val listsMutex = Mutex()
    private val namesMutex = Mutex()
    private val listsFile by lazy { File(context.noBackupFilesDir, LISTS_FILE) }

    /** Zuletzt bekannte Binance- und Alpha-Listen (direkt, Ersatzdatei oder gespeichert). */
    @Volatile private var lists: CoinMeta.Meta? = null
    @Volatile private var listsRead = false

    /** Stand der Listen; die Zuordnung übernimmt neue Listen auch ohne neue Rangliste ([withListsMerged]). */
    @Volatile private var listsVersion = 0
    @Volatile private var mergedListsVersion = -1

    /** Letzte fällige Liste scheiterte am Netz (für [retryPending]). */
    @Volatile private var listsNetworkFailed = false

    /** Letzter Abruf der Rangliste scheiterte am Netz (für [retryPending]). */
    @Volatile private var mapNetworkFailed = false

    /** [baseNames] stammen nur aus dem mitgelieferten Auszug (frische Listen haben dann Vorrang). */
    @Volatile private var namesFromSnapshot = false

    /**
     * Beim Start, ohne Netz: gespeicherte Namen, sonst der mitgelieferte Auszug (Namen und
     * Alpha-Kennzeichen, solange nie eine Liste kam) — so zeigt schon eine frische Installation
     * Namen und «α». Räumt die Ablage der früheren Ersatz-Logos (jsDelivr) weg.
     */
    private suspend fun loadStartValues() {
        loadSavedNames()
        val needNames = baseNames.isEmpty()
        val needAlpha = !hasAlphaSymbols() && _alphaSymbols.value.isEmpty()
        if (needNames || needAlpha) {
            val snapshot = withContext(Dispatchers.IO) {
                CoinMeta.parse(runCatching {
                    context.assets.open(CoinMeta.SNAPSHOT_FILE).bufferedReader().use { it.readText() }
                }.getOrNull())
            }
            if (snapshot != null) {
                if (needAlpha && snapshot.alpha.isNotEmpty() && !hasAlphaSymbols()) {
                    _alphaSymbols.value = CoinLogos.alphaSymbols(snapshot.alpha)
                }
                if (needNames) namesMutex.withLock {
                    if (baseNames.isEmpty()) {
                        baseNames = CoinMeta.listNames(snapshot.binance, snapshot.alpha)
                        namesFromSnapshot = true
                        publishNames()
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            val oldDir = File(context.cacheDir, OLD_FALLBACK_DIR)
            if (oldDir.exists()) {
                oldDir.deleteRecursively()
                File(context.noBackupFilesDir, OLD_FALLBACK_MANIFEST_FILE).delete()
                prefs.edit { remove(OLD_KEY_FALLBACK_ATTEMPT) }
            }
        }
    }

    private suspend fun savedLists(): CoinMeta.Meta? {
        if (!listsRead) {
            lists = withContext(Dispatchers.IO) { CoinMeta.parse(runCatching { listsFile.readText() }.getOrNull()) }
            listsRead = true
        }
        return lists
    }

    /**
     * Binance-Symbolliste (ohne [includeBinance] nur die Alpha-Liste) und Alpha-Liste gleichzeitig
     * holen ([CoinLists.fetch]), je höchstens einmal pro Tag, nach einem Server-Fehler frühestens nach
     * einer Stunde; kein Netz zählt nicht als Versuch. Alpha-Kennzeichen und Namen gehen **sofort** an
     * [alphaSymbols] und [names] — vor Rangliste und Bildern. true = neue Listen.
     */
    suspend fun refreshLists(includeBinance: Boolean = true): Boolean = listsMutex.withLock {
        val now = System.currentTimeMillis()
        val saved = savedLists()
        val binanceAt = prefs.getLong(KEY_BINANCE_TIME, 0L)
        val alphaAt = prefs.getLong(KEY_ALPHA_TIME, 0L)
        val binanceDue = includeBinance &&
            CoinLogos.alphaListDue(binanceAt, prefs.getLong(KEY_BINANCE_ATTEMPT, 0L), now, saved?.binance?.isNotEmpty() == true)
        // Ohne Kennungen (Liste einer älteren Version): einmal direkt neu, sonst fehlen die Alpha-Paare
        val alphaDue = CoinLogos.alphaListDue(
            alphaAt, prefs.getLong(KEY_ALPHA_ATTEMPT, 0L), now, hasAlphaSymbols() && CoinLogos.hasAlphaIds(saved?.alpha),
        )
        if (!binanceDue && !alphaDue) {
            listsNetworkFailed = false
            return@withLock false
        }
        val request = CoinLists.Request(
            binanceDue = binanceDue,
            alphaDue = alphaDue,
            lastBinanceAt = binanceAt,
            lastAlphaAt = alphaAt,
            now = now,
            mirrorAllowed = !CoinLogos.isFresh(prefs.getLong(KEY_META_ATTEMPT, 0L), now, MAP_RETRY_MILLIS),
        )
        val result = withContext(Dispatchers.IO) {
            CoinLists.fetch({ url -> listClient.callMarket(url, null, HttpBodyLimit.BULK_MAX_BYTES) }, request) { isNetworkError(it) }
        }
        val network = result.networkError || !connectivity.isOnline()
        // Versuche merken (kein Netz zählt nicht), Erfolg direkt mit Zeitpunkt
        prefs.edit {
            if (binanceDue) {
                if (result.binanceSource == CoinLists.Source.DIRECT) putLong(KEY_BINANCE_TIME, now)
                if (result.binanceSource == CoinLists.Source.DIRECT || CoinLogoRetry.recordsAttempt(network)) putLong(KEY_BINANCE_ATTEMPT, now)
            }
            if (alphaDue && (result.alphaSource == CoinLists.Source.DIRECT || CoinLogoRetry.recordsAttempt(network))) {
                putLong(KEY_ALPHA_ATTEMPT, now)
            }
            if (result.mirrorFailed && !network) putLong(KEY_META_ATTEMPT, now)
        }
        listsNetworkFailed = network && result.missing(request)
        val freshAlpha = result.alpha?.let { CoinLogos.keepAlphaIds(it, saved?.alpha.orEmpty()) }
        val freshBinance = result.binance
        if (freshAlpha == null && freshBinance == null) return@withLock false
        // Alpha-Kennzeichen sofort (direkt mit Zeitpunkt; aus der Ersatzdatei ohne, dann bald erneut direkt)
        if (freshAlpha != null) {
            val symbols = CoinLogos.alphaSymbols(freshAlpha)
            if (result.alphaSource == CoinLists.Source.DIRECT) storeAlphaFetched(symbols, now) else storeAlphaSymbols(symbols)
        }
        // Binance-Namen für «gleicher Token» (K-8): Spot vor Futures
        if (freshBinance != null) tokenIdentityStore.saveBinance(CoinMeta.binanceFullNames(freshBinance))
        val meta = CoinMeta.Meta(now, freshBinance ?: saved?.binance.orEmpty(), freshAlpha ?: saved?.alpha.orEmpty())
        lists = meta
        listsVersion++
        // Alpha-Paare von Binance und Namen für «α» sofort
        if (freshAlpha != null) publishAlphaTokens(freshAlpha)
        withContext(Dispatchers.IO) {
            runCatching {
                listsFile.parentFile?.mkdirs()
                listsFile.writeText(CoinMeta.encode(meta))
            }.onFailure { Timber.w(it, "Binance-Listen nicht gespeichert") }
        }
        // Namen sofort (gespeicherte mit CoinGecko-Namen behalten Vorrang)
        val listNames = CoinMeta.listNames(meta.binance, meta.alpha)
        namesMutex.withLock {
            loadSavedNamesLocked()
            val merged = CoinLists.publishNames(baseNames, namesFromSnapshot, listNames)
            withContext(Dispatchers.IO) {
                runCatching {
                    namesFile.parentFile?.mkdirs()
                    namesFile.writeText(CoinLogos.encodeNames(merged))
                }.onFailure { Timber.w(it, "Coin-Namen nicht gespeichert") }
            }
            baseNames = merged
            namesFromSnapshot = false
            publishNames()
        }
        Timber.i(
            "Binance-Listen: %d Einträge (%s), Alpha %d (%s), %d Namen",
            meta.binance.size, result.binanceSource, meta.alpha.size, result.alphaSource, baseNames.size,
        )
        true
    }

    private fun isNetworkError(e: Exception): Boolean =
        e is NetworkError || e is TimeoutError || e is java.io.IOException

    /** Wurde die Alpha-Liste schon einmal gespeichert? Sonst einmal die ganze Logo-Liste holen. */
    private fun hasAlphaSymbols(): Boolean = runCatching { prefs.contains(KEY_ALPHA_SYMBOLS) }.getOrDefault(false)

    private fun loadAlphaSymbols(): Set<String> =
        runCatching { prefs.getStringSet(KEY_ALPHA_SYMBOLS, null)?.toSet() }.getOrNull() ?: emptySet()

    private val _tradFiBases = MutableStateFlow(loadTradFiBases())

    /**
     * Alle TradFi-Kürzel der gespeicherten Paarlisten der Futures-Börsen der Merkliste (nicht nur
     * die beobachteten): für sie lädt [startSync] Aktien-Logos ([CoinLogos.withStockLogos]) — für
     * alle gleich, so verrät der Abruf keine einzelne Aktie.
     */
    fun setTradFiBases(bases: Set<String>): Boolean {
        if (bases == _tradFiBases.value) return false
        prefs.edit { putStringSet(KEY_TRADFI_BASES, bases) }
        _tradFiBases.value = bases
        publishNames()
        return true
    }

    /** Namen aus Rangliste und Binance-Liste (ohne Aktiennamen). */
    @Volatile private var baseNames: Map<String, String> = emptyMap()

    /** Alle US-Aktien der Nasdaq-Symbolliste: Kürzel → Name; null = noch nicht gelesen. */
    @Volatile private var stockNames: Map<String, String>? = null
    private val stockNamesFile by lazy { File(context.noBackupFilesDir, STOCK_NAMES_FILE) }
    private val stockMutex = Mutex()

    /** [names] = Namen der Listen, dazu die Aktiennamen der TradFi-Kürzel ([CoinLogos.withStockNames]). */
    private fun publishNames() {
        _names.value = CoinLogos.withStockNames(baseNames, stockNames.orEmpty(), _tradFiBases.value)
    }

    /**
     * Aktiennamen («CAT» → «Caterpillar, Inc.») aus der offiziellen Nasdaq-Symbolliste (zwei
     * Textdateien mit allen US-Aktien, für alle gleich) — nur wenn es TradFi-Kürzel gibt; höchstens
     * einmal pro Woche, nach einem Fehler frühestens nach einer Stunde erneut.
     */
    suspend fun refreshStockNames() = stockMutex.withLock {
        if (_tradFiBases.value.isEmpty()) return@withLock
        if (stockNames == null) {
            stockNames = withContext(Dispatchers.IO) {
                CoinLogos.decodeNames(runCatching { stockNamesFile.readText() }.getOrNull())
            }
            publishNames()
        }
        val now = System.currentTimeMillis()
        if (CoinLogos.isFresh(prefs.getLong(KEY_STOCK_NAMES_TIME, 0L), now, CoinLogos.STOCK_NAMES_TTL_MILLIS) && !stockNames.isNullOrEmpty()) return@withLock
        if (CoinLogos.isFresh(prefs.getLong(KEY_STOCK_NAMES_ATTEMPT, 0L), now, MAP_RETRY_MILLIS)) return@withLock
        prefs.edit { putLong(KEY_STOCK_NAMES_ATTEMPT, now) }
        val fresh = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            for (url in listOf(CoinLogos.NASDAQ_LISTED_URL, CoinLogos.OTHER_LISTED_URL)) {
                try {
                    CoinLogos.parseSymbolDirectory(apiClient.callMarket(url, null, HttpBodyLimit.BULK_MAX_BYTES), out)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Nasdaq-Symbolliste nicht verfügbar: %s", e.message)
                }
            }
            out
        }
        if (fresh.isEmpty()) return@withLock
        withContext(Dispatchers.IO) {
            runCatching {
                stockNamesFile.parentFile?.mkdirs()
                stockNamesFile.writeText(CoinLogos.encodeNames(fresh))
            }.onFailure { Timber.w(it, "Aktiennamen nicht gespeichert") }
        }
        prefs.edit {
            putLong(KEY_STOCK_NAMES_TIME, now)
            remove(OLD_KEY_STOCKS_ETAG)
        }
        stockNames = fresh
        publishNames()
    }

    private fun loadTradFiBases(): Set<String> =
        runCatching { prefs.getStringSet(KEY_TRADFI_BASES, null)?.toSet() }.getOrNull() ?: emptySet()

    private fun loadTradFiPairs(): Set<String> =
        runCatching { prefs.getStringSet(KEY_TRADFI_PAIRS, null)?.toSet() }.getOrNull() ?: emptySet()

    /** Sofort, ohne Datei und Netz: Bild aus dem Speicher oder null (mitgeliefertes Icon, falls nur das da ist). */
    fun cached(symbol: String): Bitmap? = CoinLogos.fileName(symbol)?.let { name ->
        memory.get(name) ?: CoinLogos.bundledIconName(symbol)?.let { memory.get(BUNDLED_MEMORY_PREFIX + it) }
    }

    /**
     * Logo für [symbol] aus Speicher oder Datei — nie aus dem Netz. Vorrang hat das geladene Logo der
     * Listen (CoinGecko, Binance, Alpha), sonst das mitgelieferte Icon ([bundled]). null → Initialen.
     */
    suspend fun logo(symbol: String): Bitmap? {
        val name = CoinLogos.fileName(symbol) ?: return null
        memory.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            readImage(File(imageDir, name), name) ?: bundled(symbol)
        }
    }

    /** Bild aus Datei in den Speicher ([key]); kaputte Datei wird gelöscht. */
    private fun readImage(file: File, key: String): Bitmap? {
        memory.get(key)?.let { return it }
        if (!file.isFile) return null
        val bitmap = decodeFile(file)
        if (bitmap == null) file.delete() else memory.put(key, bitmap)
        return bitmap
    }

    /** Mitgeliefertes Icon-Paket ([CoinLogos.ICON_PACK_FILE]): Bytes und Inhaltsverzeichnis, einmal gelesen. */
    private val iconPack: Pair<ByteArray, Map<String, CoinLogos.IconSlice>>? by lazy {
        val bytes = runCatching { context.assets.open(CoinLogos.ICON_PACK_FILE).use { it.readBytes() } }
            .onFailure { Timber.w("Icon-Paket nicht lesbar: %s", it.message) }
            .getOrNull()
        bytes?.let { b -> CoinLogos.parseIconPack(b)?.let { b to it } }
    }

    /** Mitgeliefertes Icon (CC0) für [symbol]; null, wenn das Paket es nicht kennt. */
    private fun bundled(symbol: String): Bitmap? {
        val name = CoinLogos.bundledIconName(symbol) ?: return null
        val key = BUNDLED_MEMORY_PREFIX + name
        memory.get(key)?.let { return it }
        val (bytes, index) = iconPack ?: return null
        val slice = index[name] ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, slice.offset, slice.length) }.getOrNull()
            ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /**
     * Erster Start: die Logos der Start-Coins ([symbols]) vor dem übrigen Abgleich holen, damit die
     * Start-Auswahl nicht erst Initialen zeigt und nach ein paar Sekunden umspringt. Die Start-Coins
     * sind für alle gleich (die grössten Coins) — die Abrufe verraten also nichts über eine
     * Merkliste; einzelne Coins einer Merkliste werden weiterhin nie einzeln geholt.
     * @return true, wenn danach alle Logos da sind (fehlt eines, zeigt die Zeile Initialen)
     */
    suspend fun ensureStarterLogos(symbols: Collection<String>): Boolean {
        val current = runCatching { symbolMap() }.getOrElse { if (it is CancellationException) throw it; emptyMap() }
        var added = 0
        val complete = withContext(Dispatchers.IO) {
            symbols.map { symbol ->
                val name = CoinLogos.fileName(symbol) ?: return@map true
                if (File(imageDir, name).isFile) return@map true
                val url = current[symbol.uppercase()] ?: return@map false
                downloadTo(name, url).also { if (it) added++ }
            }.all { it }
        }
        // Gleich in den Speicher, damit die Zeilen das Bild ohne Dateizugriff zeigen
        symbols.forEach { logo(it) }
        if (added > 0) bumpRevision(force = true)
        return complete
    }

    /**
     * Abgleich im Hintergrund: zuerst Binance- und Alpha-Liste ([refreshLists], Namen und «α» sofort),
     * dann Rangliste und die Logos aller Coins, die noch fehlen. Läuft nur einmal gleichzeitig; danach
     * [onDone] mit der Zahl neuer Logos (z. B. um Widgets neu zu zeichnen).
     * [images] = false (nur «Namen anzeigen»): nur die Listen mit den Namen, keine Bilder.
     */
    fun startSync(
        images: Boolean = true,
        onDone: suspend (added: Int) -> Unit = {},
    ) {
        synchronized(syncLock) {
            // Läuft schon einer: danach noch einmal (z. B. neue TradFi-Kürzel während des Abgleichs)
            if (syncJob?.isActive == true) {
                if (images) rerunImages = true
                return
            }
            syncJob = scope.launch {
                // 1. Binance- und Alpha-Liste vor allem anderen: unabhängig von CoinGecko und den Bildern
                runLists()
                var added = 0
                var again = images
                while (again) {
                    added += runSync(true)
                    again = synchronized(syncLock) { rerunImages.also { rerunImages = false } }
                }
                if (!images) added = runSync(false)
                noteSyncResult(_retryPending.value, System.currentTimeMillis())
                onDone(added)
            }
        }
    }

    @Volatile private var rerunImages = false

    /** Läuft gerade ein Abgleich? ([CoinLogoSync] holt erst danach nach.) */
    val isSyncing: Boolean
        get() = synchronized(syncLock) { syncJob?.isActive == true }

    /**
     * Aufeinanderfolgende, am Netz gescheiterte Abgleiche und Zeitpunkt des letzten (ms) — Grundlage
     * für den wachsenden Abstand beim Nachholen ([CoinLogoRetry.retryDelayMillis]). Nur im Speicher.
     */
    @Volatile var networkFailures = 0
        private set
    @Volatile var lastNetworkFailureAt = 0L
        private set

    private fun noteSyncResult(failedForNetwork: Boolean, now: Long) {
        if (failedForNetwork) {
            networkFailures++
            lastNetworkFailureAt = now
        } else {
            networkFailures = 0
        }
    }

    private suspend fun runLists() {
        try {
            refreshLists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Binance-Listen: Abgleich fehlgeschlagen")
        }
    }

    private suspend fun runSync(images: Boolean): Int = try {
        if (images) {
            syncAll()
        } else {
            symbolMap()
            _retryPending.value = listsNetworkFailed || mapNetworkFailed
            0
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Coin-Logos: Abgleich fehlgeschlagen")
        0
    }

    /** Ergebnis eines Bild-Durchgangs; [network] = am fehlenden Netz gescheitert. */
    private data class Downloads(val added: Int = 0, val network: Boolean = false) {
        operator fun plus(o: Downloads) = Downloads(added + o.added, network || o.network)
    }

    /**
     * Rangliste holen und alle fehlenden Logos laden. Die Bilder der grössten 1000 (Rangliste Seiten 1–4
     * mit Binance- und Alpha-Liste) beginnen schon, während die übrigen Seiten laden; danach nur noch,
     * was die übrigen Seiten dazubringen.
     */
    private suspend fun syncAll(): Int = coroutineScope {
        val early = CompletableDeferred<Map<String, String>>()
        val first = async { downloadMissing(early.await()) }
        var usedEarly = false
        val current = try {
            symbolMap { partial ->
                usedEarly = true
                early.complete(CoinLogos.withStockLogos(partial, _tradFiBases.value))
            }
        } catch (e: Throwable) {
            early.complete(emptyMap())
            throw e
        }
        // Aktien-Logos für alle TradFi-Kürzel ohne Eintrag dazu
        val wanted = CoinLogos.withStockLogos(current, _tradFiBases.value)
        if (!usedEarly) early.complete(wanted)
        var result = first.await()
        if (usedEarly) result += downloadMissing(wanted)
        _retryPending.value = listsNetworkFailed || mapNetworkFailed || result.network
        result.added
    }

    /**
     * Lädt alle Logos aus [wanted], die noch fehlen (nicht fehlgeschlagen, keine Datei): mehrere
     * gleichzeitig je Server ([PER_HOST_PARALLEL], insgesamt [MAX_PARALLEL_DOWNLOADS]). Bremst ein Server
     * nur kurz (Pause bis [SHORT_PAUSE_MAX_MILLIS]), werden seine Bilder nach der Pause gleich nachgeholt.
     */
    private suspend fun downloadMissing(all: Map<String, String>): Downloads {
        // Mobile Daten: Logos, die nur die Zusatzseiten der Rangliste bringen, erst im WLAN
        val wanted = CoinLogos.downloadable(all, loadExtraOnly(), isUnmetered())
        if (wanted.isEmpty()) return Downloads()
        val now = System.currentTimeMillis()
        val failed = loadFailures(now)
        var pending = withContext(Dispatchers.IO) {
            wanted.entries.mapNotNull { (symbol, url) ->
                val name = CoinLogos.fileName(symbol) ?: return@mapNotNull null
                if (name in failed || File(imageDir, name).isFile) null else name to url
            }.distinctBy { it.first }
        }
        if (pending.isEmpty()) return Downloads()
        val attempted = pending.size
        val added = AtomicInteger()
        val newFailures = ConcurrentHashMap<String, Long>()
        var paused: List<Pair<String, String>> = emptyList()
        for (round in 1..PAUSE_ROUNDS) {
            val pausedNow = ConcurrentLinkedQueue<Pair<String, String>>()
            coroutineScope {
                pending.map { job ->
                    async(Dispatchers.IO) {
                        when (fetchLimited(job.first, job.second)) {
                            Fetched.OK -> {
                                added.incrementAndGet()
                                bumpRevision()
                            }
                            Fetched.FAILED -> newFailures[job.first] = now
                            // Server bremst: nicht als Fehler
                            Fetched.PAUSED -> pausedNow.add(job)
                        }
                        Unit
                    }
                }.awaitAll()
            }
            paused = pausedNow.toList()
            if (paused.isEmpty() || round == PAUSE_ROUNDS) break
            val wait = shortestPauseMillis(paused) ?: break
            if (wait > SHORT_PAUSE_MAX_MILLIS) break
            delay(wait)
            pending = paused
        }
        // Am fehlenden Netz gescheitert: nicht einen Tag sperren, sondern mit dem Netz nachholen
        // (gebremste Bilder zählen nicht als Versuch — sonst galt «alle gebremst» als Netzfehler)
        val network = CoinLogoRetry.downloadsFailedForNetwork(connectivity.isOnline(), newFailures.size, attempted - paused.size)
        if (newFailures.isNotEmpty() && !network) saveFailures(loadFailures(System.currentTimeMillis()) + newFailures)
        if (added.get() > 0) bumpRevision(force = true)
        Timber.i("Coin-Logos: %d neu, %d fehlgeschlagen, %d gebremst", added.get(), newFailures.size, paused.size)
        return Downloads(added.get(), network)
    }

    /** Ein Bild mit den Grenzen je Server und insgesamt (erst Server, dann gesamt — nie gesamt blockieren). */
    private suspend fun fetchLimited(name: String, url: String): Fetched {
        val host = CoinLogoRetry.hostOf(url).orEmpty()
        val perHost = hostPermits.getOrPut(host) { Semaphore(PER_HOST_PARALLEL) }
        return perHost.withPermit { downloads.withPermit { fetchTo(name, url, imageDir) } }
    }

    /** Kürzeste verbleibende Pause der Server von [jobs] (ms); null = keiner pausiert. */
    private fun shortestPauseMillis(jobs: List<Pair<String, String>>): Long? {
        val now = System.currentTimeMillis()
        return jobs.mapNotNull { CoinLogoRetry.hostOf(it.second) }.distinct()
            .mapNotNull { host -> imagePauses[host]?.let { (it - now).coerceAtLeast(0L) } }
            .minOrNull()
    }

    /**
     * Nicht getaktetes Netz (WLAN, Ethernet)? Unbekannt oder kein Netz → false (lieber sparsam).
     * Nur für die Zusatzseiten der Rangliste und deren Logos ([CoinLogos.downloadable]).
     */
    private fun isUnmetered(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        !manager.isActiveNetworkMetered
    }.getOrDefault(false)

    private fun loadExtraOnly(): Set<String> =
        runCatching { prefs.getStringSet(KEY_EXTRA_ONLY, null)?.toSet() }.getOrNull() ?: emptySet()

    private fun saveExtraOnly(keys: Set<String>) {
        runCatching { prefs.edit { putStringSet(KEY_EXTRA_ONLY, keys) } }
    }

    /** Anzeigen auffrischen: höchstens alle [REVISION_INTERVAL_MILLIS] (sonst bei jedem Bild), [force] sofort. */
    private val lastRevisionAt = AtomicLong(0L)

    private fun bumpRevision(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force) {
            lastRevisionAt.set(now)
            _revision.update { it + 1 }
            return
        }
        val last = lastRevisionAt.get()
        if (now - last >= REVISION_INTERVAL_MILLIS && lastRevisionAt.compareAndSet(last, now)) _revision.update { it + 1 }
    }

    /** Erst die kleine Fassung, sonst das Bild aus der Rangliste. */
    private fun downloadTo(name: String, url: String, dir: File = imageDir): Boolean = fetchTo(name, url, dir) == Fetched.OK

    private enum class Fetched { OK, FAILED, PAUSED }

    /**
     * Wie [downloadTo], aber mit Pause des Bild-Servers ([CoinLogoRetry.pausesImages]): dann
     * [Fetched.PAUSED] (gespeichert in [imagePauses]), das Bild gilt nicht als fehlgeschlagen.
     */
    private fun fetchTo(name: String, url: String, dir: File): Fetched {
        val host = CoinLogoRetry.hostOf(url)
        if (host != null && isPaused(host)) return Fetched.PAUSED
        val file = File(dir, name)
        for (candidate in listOf(CoinLogos.smallUrl(url), url).distinct()) {
            try {
                val png = download(candidate) ?: continue
                writeBytes(file, png)
                return Fetched.OK
            } catch (e: ImagePause) {
                pauseHost(e.host, e.retryAfterSeconds)
                return Fetched.PAUSED
            } catch (e: Exception) {
                Timber.d("Logo %s: %s", name, e.message)
            }
        }
        return Fetched.FAILED
    }

    /** Laufende Pausen je Bild-Server (Host → bis, ms); gespeichert, damit ein Neustart sie beachtet. */
    private val imagePauses = ConcurrentHashMap<String, Long>()
    @Volatile private var pausesLoaded = false

    private fun isPaused(host: String): Boolean {
        val now = System.currentTimeMillis()
        if (!pausesLoaded) {
            imagePauses.putAll(CoinLogoRetry.decodePauses(prefs.getString(KEY_IMAGE_PAUSES, null), now))
            pausesLoaded = true
        }
        val until = imagePauses[host] ?: return false
        if (until > now) return true
        imagePauses.remove(host)
        return false
    }

    private fun pauseHost(host: String, retryAfterSeconds: Long?) {
        if (host.isEmpty()) return
        val now = System.currentTimeMillis()
        val until = now + CoinLogoRetry.imagePauseMillis(retryAfterSeconds)
        // Schon pausiert (parallele Abrufe): Pause nicht verlängern
        if ((imagePauses[host] ?: 0L) > now) return
        imagePauses[host] = until
        prefs.edit { putString(KEY_IMAGE_PAUSES, CoinLogoRetry.encodePauses(imagePauses.filterValues { it > now })) }
        Timber.i("Coin-Logos: %s bremst, Pause bis %d", host, until)
    }

    /**
     * Gespeicherte Namen in [names] bringen — nur aus der Datei, nie aus dem Netz (für die Suche über
     * den Namen, auch wenn Logos und Namen ausgeschaltet sind und kein Abgleich läuft). Wartet nie auf
     * einen laufenden Abgleich (eigene Sperre statt der der Rangliste).
     */
    suspend fun loadSavedNames() = namesMutex.withLock { loadSavedNamesLocked() }

    /** Wie [loadSavedNames]; nur mit gehaltener [namesMutex]. */
    private suspend fun loadSavedNamesLocked() {
        if (baseNames.isNotEmpty()) return
        val saved = withContext(Dispatchers.IO) {
            CoinLogos.decodeNames(readFirst(namesFile, File(context.noBackupFilesDir, OLD_FILES[1])))
        }
        if (saved.isEmpty()) return
        baseNames = saved
        namesFromSnapshot = false
        publishNames()
    }

    /**
     * Zuordnung aus Datei bzw. frisch von CoinGecko, zusammengeführt mit den Binance-Listen aus
     * [refreshLists]; bei Fehlern die alte (auch abgelaufen). Einträge der früheren eigenen Logo-Liste
     * (GitHub Pages) fallen beim Lesen weg ([CoinLogos.isAllowedUrl]) — dann wird die Liste neu geholt.
     * [onTop]: Zuordnung nach den grössten 1000 (für den frühen Start der Bilder), nur bei neuem Abruf.
     */
    private suspend fun symbolMap(onTop: (suspend (Map<String, String>) -> Unit)? = null): Map<String, String> =
        mapMutex.withLock { lockedSymbolMap(onTop) }

    private suspend fun lockedSymbolMap(onTop: (suspend (Map<String, String>) -> Unit)?): Map<String, String> {
        val now = System.currentTimeMillis()
        // Nach dem Update: bis zum ersten Abruf die Liste der Vorgängerfassung
        val known = map ?: withContext(Dispatchers.IO) {
            CoinLogos.decode(readFirst(mapFile, File(context.noBackupFilesDir, OLD_FILES[0])))
        }.also { map = it }
        loadSavedNames()
        val saved = savedLists()
        mapNetworkFailed = false
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_TIME, 0L), now) && known.isNotEmpty()) return withListsMerged(known)
        // Letzter Versuch scheiterte vor Kurzem: nicht bei jedem Start erneut fragen
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_ATTEMPT, 0L), now, MAP_RETRY_MILLIS)) return withListsMerged(known)

        val binance = saved?.binance?.takeIf { it.isNotEmpty() }
        val alpha = saved?.alpha?.takeIf { it.isNotEmpty() }
        val net = NetState()
        val top = ArrayList<CoinMeta.GeckoRow>()
        var complete = fetchCoinGecko(top, 1..CoinLogos.PAGES, net)
        if (top.isEmpty()) {
            // Kein Netz (z. B. direkt nach der Installation): nicht als Versuch zählen, sondern
            // nachholen, sobald wieder Netz da ist — sonst kämen eine Stunde lang keine Logos
            val network = net.failed || !connectivity.isOnline()
            if (CoinLogoRetry.recordsAttempt(network)) prefs.edit { putLong(KEY_MAP_ATTEMPT, now) }
            mapNetworkFailed = network
            // Binance-Listen trotzdem für die Lücken
            return withListsMerged(known)
        }
        // Geänderte Bild-Adressen der grössten 1000 gleich löschen, dann ihre Bilder schon laden
        val handled = HashSet<String>()
        if (onTop != null) {
            val partial = CoinMeta.merge(top, binance, alpha).map
            handled += dropChangedImages(known, partial)
            onTop(partial)
        }
        // Kleinere Coins (Rang 1001–2500) nur noch für die Lücken — und nur ohne getaktetes Netz (mobile
        // Daten): sonst gilt die Liste als unvollständig und wird nach einigen Stunden erneut versucht
        val extra = ArrayList<CoinMeta.GeckoRow>()
        var extraFetched = false
        if (complete) {
            if (isUnmetered()) {
                complete = fetchCoinGecko(extra, (CoinLogos.PAGES + 1)..(CoinLogos.PAGES + CoinLogos.EXTRA_PAGES), net)
                extraFetched = complete
            } else {
                complete = false
                Timber.i("Coin-Logos: getaktetes Netz, Zusatzseiten der Rangliste erst im WLAN")
            }
        }
        prefs.edit { putLong(KEY_MAP_ATTEMPT, now) }
        val merged = CoinMeta.merge(top, binance, alpha, extra)
        // Kürzel nur aus den Zusatzseiten merken: ihre Logos lädt der Sammel-Abruf nur ohne getaktetes Netz
        if (extraFetched) saveExtraOnly(CoinLogos.extraOnlyKeys(merged.map, CoinMeta.merge(top, binance, alpha).map))
        // Unvollständig (eine Quelle fehlte): Bekanntes behalten und in einigen Stunden erneut
        val full = complete && binance != null && alpha != null
        val fresh = if (full) merged.map else CoinLogos.withKnown(merged.map, known)
        val names = namesMutex.withLock {
            val n = if (full) merged.names else CoinLogos.withKnown(merged.names, baseNames)
            baseNames = n
            namesFromSnapshot = false
            publishNames()
            n
        }
        withContext(Dispatchers.IO) {
            runCatching {
                mapFile.parentFile?.mkdirs()
                mapFile.writeText(CoinLogos.encode(fresh))
                namesFile.writeText(CoinLogos.encodeNames(names))
                // Listen älterer Fassungen
                for (old in OLD_FILES) File(context.noBackupFilesDir, old).delete()
            }.onFailure { Timber.w(it, "Logo-Zuordnung nicht gespeichert") }
        }
        prefs.edit {
            putLong(KEY_MAP_TIME, CoinLogos.savedAtFor(now, full))
            remove(OLD_KEY_INDEX_ETAG)
        }
        // Geänderte Bild-Adressen (anderes Logo, andere Quelle): gespeichertes Bild neu laden
        dropChangedImages(known, fresh, skip = handled)
        Timber.i("Coin-Logos: Liste mit %d Einträgen (%s)", fresh.size, if (full) "vollständig" else "unvollständig")
        map = fresh
        mergedListsVersion = listsVersion
        return fresh
    }

    /**
     * Gespeicherte Bilder der Kürzel löschen, deren Bild-Adresse sich zwischen [old] und [new]
     * geändert hat (ausser [skip]); Ergebnis: diese Kürzel.
     */
    private suspend fun dropChangedImages(old: Map<String, String>, new: Map<String, String>, skip: Set<String> = emptySet()): Set<String> {
        val changed = CoinLogos.changedKeys(old, new) - skip
        if (changed.isEmpty()) return changed
        withContext(Dispatchers.IO) {
            for (key in changed) {
                val name = CoinLogos.fileName(key) ?: continue
                memory.remove(name)
                File(imageDir, name).delete()
            }
        }
        prefs.edit { remove(KEY_FAILED) }
        Timber.i("Coin-Logos: %d geänderte Bilder werden neu geladen", changed.size)
        return changed
    }

    /**
     * Zuordnung ohne neue Rangliste: neue Binance-/Alpha-Einträge ([refreshLists]) füllen nur Lücken
     * (Vorhandenes bleibt), damit z. B. ein neues Alpha-Token sein Logo nicht erst nach einem Tag bekommt.
     */
    private suspend fun withListsMerged(known: Map<String, String>): Map<String, String> {
        val l = lists ?: return known
        if (mergedListsVersion == listsVersion) return known
        mergedListsVersion = listsVersion
        val listMap = CoinMeta.merge(emptyList(), l.binance, l.alpha, crypto = CoinMeta.listCrypto(l.binance)).map
        val out = CoinLogos.withKnown(known, listMap)
        if (out.size == known.size) return known
        map = out
        withContext(Dispatchers.IO) {
            runCatching {
                mapFile.parentFile?.mkdirs()
                mapFile.writeText(CoinLogos.encode(out))
            }.onFailure { Timber.w(it, "Logo-Zuordnung nicht gespeichert") }
        }
        return out
    }

    private fun readFirst(vararg files: File): String? =
        files.firstNotNullOfOrNull { f -> runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() } }

    /** Merkt sich, ob eine Quelle am Netz scheiterte (keine Verbindung, Zeitüberschreitung). */
    private inner class NetState {
        @Volatile var failed = false
        fun note(e: Exception) {
            if (isNetworkError(e)) failed = true
        }
    }

    /** Eine Seite der Rangliste; bei «429» einmal nach der verlangten Pause wiederholen. */
    private suspend fun marketsPage(page: Int): String = try {
        apiClient.callMarket(CoinLogos.marketsUrl(page), null)
    } catch (e: HttpMarketError) {
        if (e.httpCode != 429) throw e
        delay(CoinLogos.rateLimitWaitMillis(e.retryAfterSeconds))
        apiClient.callMarket(CoinLogos.marketsUrl(page), null)
    }

    /** Seiten der Rangliste mit Pause dazwischen nach [out]; false = eine Seite fehlte (Abbruch). */
    private suspend fun fetchCoinGecko(out: MutableList<CoinMeta.GeckoRow>, pages: IntRange, net: NetState): Boolean {
        for (page in pages) {
            if (page > 1) delay(CoinLogos.PAGE_PAUSE_MILLIS)
            try {
                val rows = CoinMeta.parseMarketsPage(withContext(Dispatchers.IO) { marketsPage(page) })
                    ?: error("Seite $page unlesbar")
                out += rows
                if (rows.size < CoinLogos.PER_PAGE) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                net.note(e)
                Timber.w("Logo-Rangliste Seite %d nicht verfügbar: %s", page, e.message)
                return false
            }
        }
        return true
    }

    /** Fehlgeschlagene Bilder (Name → Zeitpunkt) der letzten [CoinLogos.FAILURE_TTL_MILLIS]. */
    private fun loadFailures(now: Long): Map<String, Long> =
        prefs.getString(KEY_FAILED, null).orEmpty().split(';').mapNotNull { part ->
            val name = part.substringBefore(':')
            val at = part.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
            if (name.isEmpty() || !CoinLogos.isFresh(at, now, CoinLogos.FAILURE_TTL_MILLIS)) null else name to at
        }.toMap()

    private fun saveFailures(failures: Map<String, Long>) {
        prefs.edit { putString(KEY_FAILED, failures.entries.joinToString(";") { "${it.key}:${it.value}" }) }
    }

    /** Bild-Server verlangt eine Pause ([CoinLogoRetry.pausesImages]). */
    private class ImagePause(val host: String, val retryAfterSeconds: Long?) : Exception("pause $host")

    /** Bild laden → PNG-Bytes zum Speichern; null = kein lesbares Bild. */
    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (CoinLogoRetry.pausesImages(response.code)) {
                throw ImagePause(
                    CoinLogoRetry.hostOf(url) ?: "",
                    ExchangeBackoff.parseRetryAfterSeconds(response.header("Retry-After"), System.currentTimeMillis()),
                )
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body
            // Auch ohne Content-Length nicht mehr als die Höchstgrösse lesen
            val bytes = HttpBodyLimit.read(body.byteStream(), body.contentLength(), MAX_IMAGE_BYTES)
                ?: error("too large")
            return pngBytes(bytes)
        }
    }

    /**
     * Zu speichernde PNG-Bytes: schon ein PNG in passender Grösse (CoinGecko «small», viele
     * Binance-Logos) → unverändert, ohne Umrechnen (spart Zeit beim Laden aller Logos); sonst
     * verkleinert auf [CoinLogos.STORED_PX] und als PNG.
     */
    private fun pngBytes(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (isPng(bytes) && max(bounds.outWidth, bounds.outHeight) <= CoinLogos.STORED_PX) return bytes
        val bitmap = decodeScaled(bytes, bounds) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /** Verkleinert beim Dekodieren (inSampleSize) und danach genau auf [CoinLogos.STORED_PX]. */
    private fun decodeScaled(bytes: ByteArray, bounds: BitmapFactory.Options): Bitmap? {
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CoinLogos.STORED_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= CoinLogos.STORED_PX) return decoded
        val scale = CoinLogos.STORED_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    }

    private fun decodeFile(file: File): Bitmap? = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()

    /** Erst in eine Hilfsdatei, dann umbenennen (nie ein halbes Bild). */
    private fun writeBytes(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    // Zuletzt: alle Felder sind gesetzt, bevor der Start im Hintergrund sie liest
    init {
        scope.launch {
            try {
                loadStartValues()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Coin-Namen: Startwerte nicht gelesen")
            }
        }
    }

    private companion object {
        const val PREFS = "coin_logos"
        // «v5»: Einträge der früheren GitHub-Liste fallen beim Lesen weg (isAllowedUrl), dann neu direkt
        const val KEY_MAP_TIME = "map_time_v5"
        const val KEY_MAP_ATTEMPT = "map_attempt_v5"
        const val KEY_FAILED = "failed"
        /** Kennungen der früheren GitHub-Liste (nicht mehr benutzt, werden gelöscht). */
        const val OLD_KEY_INDEX_ETAG = "index_etag_v5"
        const val OLD_KEY_STOCKS_ETAG = "stocks_etag_v1"
        const val KEY_TRADFI_PAIRS = "tradfi_pairs"
        /** Kürzel, die nur die Zusatzseiten der Rangliste bringen ([CoinLogos.extraOnlyKeys]). */
        const val KEY_EXTRA_ONLY = "extra_only_v1"
        const val KEY_TRADFI_BASES = "tradfi_bases"
        const val KEY_ALPHA_SYMBOLS = "alpha_symbols_v1"
        /** Zeitpunkt der zuletzt geholten Alpha-Liste bzw. des letzten Versuchs allein ([refreshAlphaOnly]). */
        const val KEY_ALPHA_TIME = "alpha_time_v1"
        const val KEY_ALPHA_ATTEMPT = "alpha_attempt_v1"
        const val KEY_STOCK_NAMES_TIME = "stock_names_time_v1"
        const val KEY_STOCK_NAMES_ATTEMPT = "stock_names_attempt_v1"
        const val STOCK_NAMES_FILE = "coin_logos/stock_names1.txt"
        const val MAP_FILE = "coin_logos/map5.txt"
        const val NAMES_FILE = "coin_logos/names5.txt"
        val OLD_FILES = listOf("coin_logos/map4.txt", "coin_logos/names4.txt")
        const val IMAGE_DIR = "coin_logos"
        /** Zuletzt geholte Binance- und Alpha-Liste (Format wie `coin_meta.json`, [CoinMeta.encode]). */
        const val LISTS_FILE = "coin_logos/lists1.json"
        /** Zeitpunkt der zuletzt direkt geholten Binance-Symbolliste bzw. des letzten Versuchs. */
        const val KEY_BINANCE_TIME = "binance_list_time_v1"
        const val KEY_BINANCE_ATTEMPT = "binance_list_attempt_v1"
        /** Letzter unbrauchbarer Abruf der Ersatzdatei `coin_meta.json` (dann frühestens nach einer Stunde). */
        const val KEY_META_ATTEMPT = "coin_meta_attempt_v1"
        /** Mitgelieferte Icons im Speicher (eigene Vorsilbe: Vorrang haben die geladenen Logos). */
        const val BUNDLED_MEMORY_PREFIX = "B_"
        /** Ablage der früheren Ersatz-Logos über jsDelivr (wird einmal gelöscht). */
        const val OLD_FALLBACK_DIR = "coin_logos_fallback"
        const val OLD_FALLBACK_MANIFEST_FILE = "coin_logos/fallback_manifest_0_18_1.json"
        const val OLD_KEY_FALLBACK_ATTEMPT = "fallback_attempt_v1"
        /** Pausen je Bild-Server ([CoinLogoRetry.encodePauses]). */
        const val KEY_IMAGE_PAUSES = "image_pauses_v1"
        /** Wie iOS («cryptoChecker-iOS/16»); ohne Angabe lehnen manche Bild-Server Abrufe ab. */
        const val USER_AGENT = "cryptoChecker-Android/16"
        const val MEMORY_BYTES = 4 * 1024 * 1024
        /** Bild-Abrufe gleichzeitig: insgesamt und je Server (CoinGecko, Binance). */
        const val MAX_PARALLEL_DOWNLOADS = 12
        const val PER_HOST_PARALLEL = 6
        const val MAX_IMAGE_BYTES = 512L * 1024

        /** Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt. */
        const val SHORT_PAUSE_MAX_MILLIS = 2 * 60_000L
        const val PAUSE_ROUNDS = 3

        /** Anfang jeder PNG-Datei. */
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Zeitgrenze für Binance- und Alpha-Liste (wie die Paarlisten). */
        const val LIST_TIMEOUT_SECONDS = 60L

        /** Anzeigen während des Ladens höchstens so oft auffrischen. */
        const val REVISION_INTERVAL_MILLIS = 1_000L

        /** Rangliste nicht erreichbar: frühestens nach einer Stunde erneut. */
        const val MAP_RETRY_MILLIS = 60L * 60 * 1000
    }
}
