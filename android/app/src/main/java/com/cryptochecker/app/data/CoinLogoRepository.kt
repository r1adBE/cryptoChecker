package com.cryptochecker.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.logos.CoinLogos
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Coin-Logos (Regeln in [CoinLogos]). Nichts ist mitgeliefert, und nichts wird
 * einzeln geholt — so erfährt CoinGecko nie, welche Coins in einer Merkliste stehen:
 *
 * 1. Eigene Logo-Liste auf GitHub Pages ([CoinLogos.INDEX_URL], täglich von einer GitHub Action
 *    aus CoinGecko, Binance, OKX und Nasdaq gebaut, Krypto und TradFi, Bilder daneben; beim ersten
 *    Laden alle Bilder in einem Abruf aus [CoinLogos.PACK_URL]). Nur solange es die Liste nie gab: Rangliste von CoinGecko,
 *    Binance-Liste, Alpha-Liste direkt. Höchstens einmal pro Woche, für alle gleich. Fehlte eine
 *    Quelle, bleibt Bekanntes erhalten und der Abruf wird nach einigen Stunden wiederholt;
 *    geänderte Bild-Adressen laden das Bild neu.
 * 2. [startSync] lädt die Logos **aller** Coins dieser Liste (kleine Fassung, auf
 *    [CoinLogos.STORED_PX] begrenzt) und legt sie als PNG im Cache-Ordner ab. Später fehlen nur
 *    neu dazugekommene Coins; ohne Neues geht kein Bild-Abruf ins Netz.
 * 3. Angezeigt wird nur aus Speicher und Datei ([logo]); Coins ausserhalb der Liste, DEX-Pools
 *    und Fehler → Initialen. Ein fehlgeschlagenes Bild wird einen Tag lang nicht wiederholt.
 *
 * Alle drei Schalter «Coin-Logos» aus → [startSync] wird nicht aufgerufen, also nichts geladen.
 */
@Singleton
class CoinLogoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
) {
    /** Gleiche Verbindungen, aber ohne Mitschnitt (sonst landen Bild-Bytes im Debug-Protokoll). */
    private val client: OkHttpClient = httpClient.newBuilder().apply { interceptors().clear() }.build()
    private val apiClient: OkHttpClient = httpClient

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Any()
    private var syncJob: Job? = null

    private val _revision = MutableStateFlow(0)

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
        if (CoinLogos.isFresh(prefs.getLong(KEY_STOCK_NAMES_TIME, 0L), now) && !stockNames.isNullOrEmpty()) return@withLock
        if (CoinLogos.isFresh(prefs.getLong(KEY_STOCK_NAMES_ATTEMPT, 0L), now, MAP_RETRY_MILLIS)) return@withLock
        prefs.edit { putLong(KEY_STOCK_NAMES_ATTEMPT, now) }
        val fromIndex = CoinLogos.isFromIndex(symbolMap())
        var stocksEtag: String? = null
        val fresh = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            if (fromIndex) {
                // Mit GitHub-Liste: eine Datei mit allen Aktiennamen statt der beiden Nasdaq-Dateien;
                // mit der Kennung des letzten Stands — «304» = unverändert, nichts geladen
                val etag = prefs.getString(KEY_STOCKS_ETAG, null)?.takeIf { !stockNames.isNullOrEmpty() }
                val request = Request.Builder().url(CoinLogos.STOCKS_URL)
                    .apply { if (etag != null) header("If-None-Match", etag) }
                    .build()
                try {
                    apiClient.newCall(request).execute().use { response ->
                        when {
                            response.code == 304 && etag != null -> {
                                stocksEtag = etag
                                out.putAll(stockNames.orEmpty())
                            }
                            !response.isSuccessful -> error("HTTP ${response.code}")
                            else -> {
                                stocksEtag = response.header("ETag")
                                out.putAll(CoinLogos.decodeNames(response.body.string()))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w("Aktiennamen von GitHub nicht verfügbar: %s", e.message)
                }
                return@withContext out
            }
            for (url in listOf(CoinLogos.NASDAQ_LISTED_URL, CoinLogos.OTHER_LISTED_URL)) {
                try {
                    CoinLogos.parseSymbolDirectory(apiClient.callMarket(url, null), out)
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
            if (stocksEtag != null) putString(KEY_STOCKS_ETAG, stocksEtag) else remove(KEY_STOCKS_ETAG)
        }
        stockNames = fresh
        publishNames()
    }

    private fun loadTradFiBases(): Set<String> =
        runCatching { prefs.getStringSet(KEY_TRADFI_BASES, null)?.toSet() }.getOrNull() ?: emptySet()

    private fun loadTradFiPairs(): Set<String> =
        runCatching { prefs.getStringSet(KEY_TRADFI_PAIRS, null)?.toSet() }.getOrNull() ?: emptySet()

    /** Sofort, ohne Datei und Netz: Bild aus dem Speicher oder null. */
    fun cached(symbol: String): Bitmap? = CoinLogos.fileName(symbol)?.let { memory.get(it) }

    /** Logo für [symbol] aus Speicher oder Datei — nie aus dem Netz. null → Initialen. */
    suspend fun logo(symbol: String): Bitmap? {
        val name = CoinLogos.fileName(symbol) ?: return null
        memory.get(name)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(imageDir, name)
            if (!file.isFile) return@withContext null
            val bitmap = decodeFile(file)
            if (bitmap == null) file.delete() else memory.put(name, bitmap)
            bitmap
        }
    }

    /**
     * Lädt im Hintergrund die Logos aller Coins der Rangliste, die noch fehlen. Läuft nur einmal
     * gleichzeitig; danach [onDone] mit der Zahl neuer Logos (z. B. um Widgets neu zu zeichnen).
     * [images] = false (nur «Namen anzeigen»): nur die Liste mit den Namen, keine Bilder.
     */
    fun startSync(images: Boolean = true, onDone: suspend (added: Int) -> Unit = {}) {
        synchronized(syncLock) {
            // Läuft schon einer: danach noch einmal (z. B. neue TradFi-Kürzel während des Abgleichs)
            if (syncJob?.isActive == true) {
                if (images) rerunImages = true
                return
            }
            syncJob = scope.launch {
                var added = 0
                var again = images
                while (again) {
                    added += runSync(true)
                    again = synchronized(syncLock) { rerunImages.also { rerunImages = false } }
                }
                if (!images) added = runSync(false)
                onDone(added)
            }
        }
    }

    @Volatile private var rerunImages = false

    private suspend fun runSync(images: Boolean): Int = try {
        if (images) syncAll() else { symbolMap(); 0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Coin-Logos: Abgleich fehlgeschlagen")
        0
    }

    private suspend fun syncAll(): Int {
        val current = symbolMap()
        val fromIndex = CoinLogos.isFromIndex(current)
        // Ohne GitHub-Liste: Aktien-Logos für alle TradFi-Kürzel ohne Eintrag dazu (mit Liste sind sie drin)
        val wanted = if (fromIndex) current else CoinLogos.withStockLogos(current, _tradFiBases.value)
        if (wanted.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val failed = loadFailures(now)
        var missing = wanted.entries.mapNotNull { (symbol, url) ->
            val name = CoinLogos.fileName(symbol) ?: return@mapNotNull null
            if (name in failed || File(imageDir, name).isFile) null else name to url
        }
        if (missing.isEmpty()) return 0
        val added = AtomicInteger()
        // Viele fehlen (erstes Laden): alle Logos in einem Abruf aus dem Paket
        if (fromIndex && missing.size >= CoinLogos.PACK_MIN_MISSING) {
            val fromPack = withContext(Dispatchers.IO) { fillFromPack(missing) }
            if (fromPack.isNotEmpty()) {
                added.addAndGet(fromPack.size)
                _revision.value++
                missing = missing.filter { it.first !in fromPack }
            }
            if (missing.isEmpty()) {
                Timber.i("Coin-Logos: %d aus dem Paket", added.get())
                return added.get()
            }
        }
        val newFailures = ConcurrentHashMap<String, Long>()
        coroutineScope {
            missing.map { (name, url) ->
                async {
                    if (downloads.withPermit { downloadTo(name, url) }) {
                        // In Schüben melden, damit die Zeilen nicht bei jedem Bild neu laden
                        if (added.incrementAndGet() % REVISION_STEP == 0) _revision.value++
                    } else {
                        newFailures[name] = now
                    }
                }
            }.awaitAll()
        }
        if (newFailures.isNotEmpty()) saveFailures(failed + newFailures)
        if (added.get() % REVISION_STEP != 0) _revision.value++
        Timber.i("Coin-Logos: %d neu, %d fehlgeschlagen", added.get(), newFailures.size)
        return added.get()
    }

    /**
     * Lädt das Paket ([CoinLogos.PACK_URL]) und legt die Bilder für [missing] (Dateiname → Adresse)
     * ab; gibt die Dateinamen der abgelegten Bilder zurück (leer bei Fehler).
     */
    private fun fillFromPack(missing: List<Pair<String, String>>): Set<String> {
        val pack = try {
            client.newCall(Request.Builder().url(CoinLogos.PACK_URL).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > CoinLogos.PACK_MAX_BYTES) error("too large")
                val bytes = body.bytes()
                if (bytes.size > CoinLogos.PACK_MAX_BYTES) error("too large")
                CoinLogos.parsePack(bytes)
            }
        } catch (e: Exception) {
            Timber.w("Logo-Paket nicht verfügbar: %s", e.message)
            null
        } ?: return emptySet()
        val done = HashSet<String>()
        for ((name, url) in missing) {
            if (!url.startsWith(CoinLogos.INDEX_BASE)) continue
            val bytes = pack[url.removePrefix(CoinLogos.INDEX_BASE)] ?: continue
            val bitmap = decodeScaled(bytes) ?: continue
            writePng(File(imageDir, name), bitmap)
            bitmap.recycle()
            if (File(imageDir, name).isFile) done += name
        }
        return done
    }

    /** Erst die kleine Fassung, sonst das Bild aus der Rangliste. */
    private fun downloadTo(name: String, url: String): Boolean {
        val file = File(imageDir, name)
        for (candidate in listOf(CoinLogos.smallUrl(url), url).distinct()) {
            try {
                val bitmap = download(candidate) ?: continue
                writePng(file, bitmap)
                bitmap.recycle()
                return true
            } catch (e: Exception) {
                Timber.d("Logo %s: %s", name, e.message)
            }
        }
        return false
    }

    /** Zuordnung aus Datei bzw. frisch von CoinGecko; bei Fehlern die alte (auch abgelaufen). */
    private suspend fun symbolMap(): Map<String, String> = mapMutex.withLock { lockedSymbolMap() }

    private suspend fun lockedSymbolMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        // Nach dem Update: bis zum ersten Abruf die Liste der Vorgängerfassung
        val known = map ?: withContext(Dispatchers.IO) {
            CoinLogos.decode(readFirst(mapFile, File(context.noBackupFilesDir, OLD_FILES[0])))
        }.also { map = it }
        if (baseNames.isEmpty()) {
            baseNames = withContext(Dispatchers.IO) {
                CoinLogos.decodeNames(readFirst(namesFile, File(context.noBackupFilesDir, OLD_FILES[1])))
            }
            publishNames()
        }
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_TIME, 0L), now) && known.isNotEmpty()) return known
        // Letzter Versuch scheiterte vor Kurzem: nicht bei jedem Start erneut fragen
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_ATTEMPT, 0L), now, MAP_RETRY_MILLIS)) return known
        prefs.edit { putLong(KEY_MAP_ATTEMPT, now) }

        val fetchedNames = LinkedHashMap<String, String>()
        val fetch = withContext(Dispatchers.IO) { fetchMap(fetchedNames, known) }
        if (fetch.map.isEmpty()) return known
        // Unvollständig (eine Quelle fehlte): Bekanntes behalten und in einigen Stunden erneut
        val fresh = if (fetch.complete) fetch.map else CoinLogos.withKnown(fetch.map, known)
        val names = if (fetch.complete) fetchedNames else CoinLogos.withKnown(fetchedNames, baseNames)
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
            putLong(KEY_MAP_TIME, CoinLogos.savedAtFor(now, fetch.complete))
            if (fetch.etag != null) putString(KEY_INDEX_ETAG, fetch.etag) else remove(KEY_INDEX_ETAG)
        }
        // Geänderte Bild-Adressen (anderes Logo, andere Quelle): gespeichertes Bild neu laden
        val changed = CoinLogos.changedKeys(known, fresh)
        if (changed.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (key in changed) {
                    val name = CoinLogos.fileName(key) ?: continue
                    memory.remove(name)
                    File(imageDir, name).delete()
                }
            }
            prefs.edit { remove(KEY_FAILED) }
            Timber.i("Coin-Logos: %d geänderte Bilder werden neu geladen", changed.size)
        }
        Timber.i("Coin-Logos: Liste mit %d Einträgen (%s)", fresh.size, if (fetch.complete) "vollständig" else "unvollständig")
        map = fresh
        if (names.isNotEmpty()) {
            baseNames = names
            publishNames()
        }
        return fresh
    }

    private fun readFirst(vararg files: File): String? =
        files.firstNotNullOfOrNull { f -> runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() } }

    /** Ergebnis eines Abrufs; [complete] = keine Quelle ist ausgefallen; [etag] der Logo-Liste. */
    private class Fetch(val map: Map<String, String>, val complete: Boolean, val etag: String? = null)

    /** Logo-Liste: neu ([index]), unverändert seit dem letzten Mal ([unchanged]) oder nicht erreichbar. */
    private class IndexResult(val index: CoinLogos.Index? = null, val unchanged: Boolean = false, val etag: String? = null)

    /**
     * CoinGecko Seite für Seite (bricht bei einem Fehler ab und behält, was da ist), danach füllen
     * die Binance-Symbolliste (TradFi wie Gold, Silber, Aktien) und die Alpha-Liste die Lücken,
     * zuletzt Rang 1001–2500. [Fetch.complete] = false, sobald eine Quelle fehlte.
     */
    private suspend fun fetchMap(names: LinkedHashMap<String, String>, known: Map<String, String>): Fetch {
        // 1. Eigene Logo-Liste auf GitHub: alle Logos und Namen (Krypto und TradFi) in einer Datei
        val result = fetchIndex(CoinLogos.isFromIndex(known))
        if (result.unchanged) {
            // «Nichts geändert» (304): nichts geladen, Bekanntes gilt wieder eine Woche
            names.putAll(baseNames)
            return Fetch(LinkedHashMap(known), complete = true, etag = result.etag)
        }
        result.index?.let { index ->
            names.putAll(index.names)
            return Fetch(LinkedHashMap(index.logos), complete = true, etag = result.etag)
        }
        // Schon auf der GitHub-Liste: nicht auf die alten Quellen wechseln (sonst würden alle
        // Bilder neu geladen) — später erneut versuchen
        if (CoinLogos.isFromIndex(known)) return Fetch(emptyMap(), false)
        // 2. Ersatz, solange es die Liste nicht gibt: CoinGecko, Binance, Alpha direkt
        val out = LinkedHashMap<String, String>()
        var complete = fetchCoinGecko(out, names, 1..CoinLogos.PAGES)
        val crypto = out.keys.toSet()
        complete = fetchBinance(out, crypto, names) && complete
        complete = fetchAlpha(out, names) && complete
        // Kleinere Coins (Rang 1001–2500) nur noch für die Lücken
        complete = fetchCoinGecko(out, names, (CoinLogos.PAGES + 1)..(CoinLogos.PAGES + CoinLogos.EXTRA_PAGES)) && complete
        return Fetch(out, complete)
    }

    /**
     * Logo-Liste von GitHub Pages ([CoinLogos.INDEX_URL]). Mit der Kennung des letzten Stands
     * («If-None-Match»): ist nichts neu, antwortet GitHub nur «304» ohne Inhalt.
     */
    private fun fetchIndex(knownFromIndex: Boolean): IndexResult {
        val etag = if (knownFromIndex) prefs.getString(KEY_INDEX_ETAG, null) else null
        val request = Request.Builder().url(CoinLogos.INDEX_URL)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        return try {
            apiClient.newCall(request).execute().use { response ->
                when {
                    response.code == 304 && etag != null -> IndexResult(unchanged = true, etag = etag)
                    !response.isSuccessful -> error("HTTP ${response.code}")
                    else -> IndexResult(CoinLogos.parseIndex(response.body.string()), etag = response.header("ETag"))
                }
            }
        } catch (e: Exception) {
            Timber.w("Logo-Liste von GitHub nicht verfügbar: %s", e.message)
            IndexResult()
        }
    }

    /** Eine Seite der Rangliste; bei «429» einmal nach der verlangten Pause wiederholen. */
    private suspend fun marketsPage(page: Int): String = try {
        apiClient.callMarket(CoinLogos.marketsUrl(page), null)
    } catch (e: HttpMarketError) {
        if (e.httpCode != 429) throw e
        kotlinx.coroutines.delay(CoinLogos.rateLimitWaitMillis(e.retryAfterSeconds))
        apiClient.callMarket(CoinLogos.marketsUrl(page), null)
    }

    /** Seiten der Rangliste mit Pause dazwischen; false = eine Seite fehlte (Abbruch). */
    private suspend fun fetchCoinGecko(
        out: LinkedHashMap<String, String>,
        names: LinkedHashMap<String, String>,
        pages: IntRange,
    ): Boolean {
        for (page in pages) {
            if (page > 1) kotlinx.coroutines.delay(CoinLogos.PAGE_PAUSE_MILLIS)
            try {
                val array = JSONArray(marketsPage(page))
                val ranked = (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    o.optString("symbol") to (if (o.isNull("image")) null else o.optString("image"))
                }
                CoinLogos.pick(ranked, out)
                CoinLogos.pickNames((0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    o.optString("symbol") to (if (o.isNull("name")) null else o.optString("name"))
                }, names)
                if (array.length() < CoinLogos.PER_PAGE) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Logo-Rangliste Seite %d nicht verfügbar: %s", page, e.message)
                return false
            }
        }
        return true
    }

    /**
     * Inoffizielle Liste der Binance-Website: füllt Lücken der Krypto-Logos und liefert die
     * TradFi-Logos ([CoinLogos.pickTradFi]); fehlt sie, bleiben Initialen.
     */
    private suspend fun fetchBinance(
        out: LinkedHashMap<String, String>,
        crypto: Set<String>,
        names: LinkedHashMap<String, String>,
        tradFiOnly: Boolean = false,
    ): Boolean {
        try {
            val array = JSONObject(apiClient.callMarket(CoinLogos.BINANCE_LIST_URL, null)).optJSONArray("data") ?: return false
            val entries = (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val logo = if (o.isNull("logo")) null else o.optString("logo")
                val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
                CoinLogos.BinanceEntry(
                    name = o.optString("name").ifEmpty { o.optString("baseAsset") },
                    logo = logo,
                    tags = tags,
                    onlyFutures = o.optBoolean("onlyFutures", false),
                    fullName = if (o.isNull("fullName")) null else o.optString("fullName"),
                )
            }
            if (!tradFiOnly) CoinLogos.pick(entries.map { it.name to it.logo }, out)
            CoinLogos.pickTradFi(entries, crypto, out)
            if (!tradFiOnly) CoinLogos.pickNames(entries.map { it.name to it.fullName }, names)
            CoinLogos.pickTradFiNames(entries, crypto, names)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Binance-Symbolliste nicht verfügbar: %s", e.message)
            return false
        }
    }

    /** Token-Liste von Binance Alpha ([CoinLogos.ALPHA_LIST_URL]): Lücken wie AIA, AGT, AIO. */
    private suspend fun fetchAlpha(out: LinkedHashMap<String, String>, names: LinkedHashMap<String, String>): Boolean {
        try {
            val array = JSONObject(apiClient.callMarket(CoinLogos.ALPHA_LIST_URL, null)).optJSONArray("data") ?: return false
            val entries = CoinLogos.rankAlpha((0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                CoinLogos.AlphaEntry(
                    symbol = o.optString("symbol"),
                    name = if (o.isNull("name")) null else o.optString("name"),
                    icon = if (o.isNull("iconUrl")) null else o.optString("iconUrl"),
                    marketCap = o.optString("marketCap").toDoubleOrNull(),
                    offline = o.optBoolean("offline", false),
                )
            })
            CoinLogos.pick(entries.map { it.symbol to it.icon }, out)
            CoinLogos.pickNames(entries.map { it.symbol to it.name }, names)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Alpha-Tokenliste nicht verfügbar: %s", e.message)
            return false
        }
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

    private fun download(url: String): Bitmap? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES) error("too large")
            val bytes = body.bytes()
            if (bytes.size > MAX_IMAGE_BYTES) error("too large")
            return decodeScaled(bytes)
        }
    }

    /** Verkleinert beim Dekodieren (inSampleSize) und danach genau auf [CoinLogos.STORED_PX]. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
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

    private fun writePng(file: File, bitmap: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    private companion object {
        const val PREFS = "coin_logos"
        // «v5»: Logo-Liste von GitHub (davor CoinGecko direkt); eine unvollständige v4-Liste galt eine Woche — einmal neu holen
        const val KEY_MAP_TIME = "map_time_v5"
        const val KEY_MAP_ATTEMPT = "map_attempt_v5"
        const val KEY_FAILED = "failed"
        const val KEY_INDEX_ETAG = "index_etag_v5"
        const val KEY_STOCKS_ETAG = "stocks_etag_v1"
        const val KEY_TRADFI_PAIRS = "tradfi_pairs"
        const val KEY_TRADFI_BASES = "tradfi_bases"
        const val KEY_STOCK_NAMES_TIME = "stock_names_time_v1"
        const val KEY_STOCK_NAMES_ATTEMPT = "stock_names_attempt_v1"
        const val STOCK_NAMES_FILE = "coin_logos/stock_names1.txt"
        const val MAP_FILE = "coin_logos/map5.txt"
        const val NAMES_FILE = "coin_logos/names5.txt"
        val OLD_FILES = listOf("coin_logos/map4.txt", "coin_logos/names4.txt")
        const val IMAGE_DIR = "coin_logos"
        const val MEMORY_BYTES = 4 * 1024 * 1024
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val MAX_IMAGE_BYTES = 512L * 1024

        /** Anzeigen alle so viele neue Logos auffrischen. */
        const val REVISION_STEP = 25

        /** Rangliste nicht erreichbar: frühestens nach einer Stunde erneut. */
        const val MAP_RETRY_MILLIS = 60L * 60 * 1000
    }
}
