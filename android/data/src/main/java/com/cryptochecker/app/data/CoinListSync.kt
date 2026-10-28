package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.NetworkError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.util.appScope
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.domain.logos.alphaSymbols
import com.cryptochecker.app.domain.logos.hasAlphaIds
import com.cryptochecker.app.domain.logos.keepAlphaIds
import com.cryptochecker.app.domain.logos.withStockLogos

/**
 * Abgleich der Listen hinter Logos und Namen (Regeln in [CoinLogos], [CoinMeta], [CoinLists]):
 *
 * 1. Binance-Symbolliste und Alpha-Liste ([refreshLists]), Ersatzdatei `coin_meta.json` (Release «data»), wenn
 *    eine direkt fehlt — Namen ([CoinNames]) und «α» ([AlphaTokens]) gleich danach.
 * 2. Rangliste von CoinGecko ([symbolMap]), zusammengeführt mit den Listen aus 1 zur Zuordnung Kürzel → Bild.
 * 3. [startSync]: beides im Hintergrund, danach die fehlenden Bilder ([LogoImageStore.downloadMissing]).
 *
 * Sperren (je eigene, nie ineinander): [listsMutex] für die Listen, [mapMutex] für die Zuordnung, [syncLock] für
 * den laufenden Abgleich (nur kurz, nie über eine Unterbrechung). Unter [listsMutex] bzw. [mapMutex] werden
 * [CoinNames] (dessen Namen-Sperre), [AlphaTokens] und [LogoImageStore] (keine Mutex) gerufen — Reihenfolge immer
 * CoinListSync → CoinNames, nie umgekehrt. Teil von [CoinLogoRepository].
 */
@Singleton
class CoinListSync @Inject constructor(
    @param:ApplicationContext val context: Context,
    httpClient: OkHttpClient,
    val connectivity: ConnectivityMonitor,
    private val tokenIdentityStore: TokenIdentityStore,
    val imageStore: LogoImageStore,
    val names: CoinNames,
    private val alpha: AlphaTokens,
) {
    val apiClient: OkHttpClient = httpClient

    /**
     * Binance- und Alpha-Liste (je mehrere MB): längere Zeitgrenze wie bei den Paarlisten — mit den
     * 20 s für Kurse brach die Alpha-Liste bei langsamem Netz ab und die Alpha-Kürzel kamen nie an.
     */
    private val listClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val prefs by lazy { CoinLogoStorage.prefs(context) }
    val mapFile by lazy { File(context.noBackupFilesDir, MAP_FILE) }
    private val listsFile by lazy { File(context.noBackupFilesDir, LISTS_FILE) }

    private val scope = appScope(Dispatchers.IO, "CoinListSync")

    // ---- 1. Binance-Symbolliste und Alpha-Liste (Namen, Alpha-Kennzeichen) — vor allem anderen

    private val listsMutex = Mutex()

    /** Zuletzt bekannte Binance- und Alpha-Listen (direkt, Ersatzdatei oder gespeichert). */
    @Volatile var lists: CoinMeta.Meta? = null
    @Volatile private var listsRead = false

    /** Stand der Listen; die Zuordnung übernimmt neue Listen auch ohne neue Rangliste ([withListsMerged]). */
    @Volatile var listsVersion = 0
    @Volatile var mergedListsVersion = -1

    /** Letzte fällige Liste scheiterte am Netz (für [retryPending]). */
    @Volatile private var listsNetworkFailed = false

    /**
     * Beim Start, ohne Netz: gespeicherte Namen, sonst der mitgelieferte Auszug (Namen und
     * Alpha-Kennzeichen, solange nie eine Liste kam) — so zeigt schon eine frische Installation
     * Namen und «α». Räumt die Ablage der früheren Ersatz-Logos (jsDelivr) weg.
     */
    private suspend fun loadStartValues() {
        names.loadSavedNames()
        val needNames = names.isEmpty
        val needAlpha = !alpha.hasStoredSymbols() && alpha.symbols.value.isEmpty()
        if (needNames || needAlpha) {
            val snapshot = withContext(Dispatchers.IO) {
                CoinMeta.parse(runCatchingCancellable {
                    context.assets.open(CoinMeta.SNAPSHOT_FILE).bufferedReader().use { it.readText() }
                }.getOrNull())
            }
            if (snapshot != null) {
                if (needAlpha && snapshot.alpha.isNotEmpty() && !alpha.hasStoredSymbols()) {
                    alpha.showSnapshot(CoinLogos.alphaSymbols(snapshot.alpha))
                }
                if (needNames) names.showSnapshotIfEmpty { CoinMeta.listNames(snapshot.binance, snapshot.alpha) }
            }
        }
        imageStore.removeOldFallback()
    }

    suspend fun savedLists(): CoinMeta.Meta? {
        if (!listsRead) {
            lists = withContext(Dispatchers.IO) { CoinMeta.parse(runCatchingCancellable { listsFile.readText() }.getOrNull()) }
            listsRead = true
        }
        return lists
    }

    /** Gespeicherte Alpha-Token (nie Netz); liest die gespeicherten Listen beim ersten Aufruf. */
    suspend fun loadAlphaTokens(): List<BinanceAlpha.Token> {
        if (alpha.tokens.value.isEmpty()) savedLists()?.alpha?.takeIf { it.isNotEmpty() }?.let { alpha.publishTokens(it) }
        return alpha.tokens.value
    }

    /**
     * Nur die Alpha-Liste holen ([CoinLogos.alphaOnly]: Logos und Namen aus, «inkl. Alpha-Tokens» an) —
     * ohne Rangliste und ohne Bilder, für alle gleich; höchstens einmal pro Tag ([refreshLists]).
     */
    suspend fun refreshAlphaOnly() {
        refreshLists(includeBinance = false)
    }

    /**
     * Binance-Symbolliste (ohne [includeBinance] nur die Alpha-Liste) und Alpha-Liste gleichzeitig
     * holen ([CoinLists.fetch]), je höchstens einmal pro Tag, nach einem Server-Fehler frühestens nach
     * einer Stunde; kein Netz zählt nicht als Versuch. Alpha-Kennzeichen und Namen gehen **sofort** an
     * [AlphaTokens.symbols] und [CoinNames.names] — vor Rangliste und Bildern. true = neue Listen.
     */
    suspend fun refreshLists(includeBinance: Boolean = true): Boolean = listsMutex.withLock {
        val now = System.currentTimeMillis()
        val saved = savedLists()
        val binanceAt = prefs.getLong(KEY_BINANCE_TIME, 0L)
        val alphaAt = alpha.fetchedAt()
        val binanceDue = includeBinance &&
            CoinLogos.alphaListDue(binanceAt, prefs.getLong(KEY_BINANCE_ATTEMPT, 0L), now, saved?.binance?.isNotEmpty() == true)
        // Ohne Kennungen (Liste einer älteren Version): einmal direkt neu, sonst fehlen die Alpha-Paare
        val alphaDue = CoinLogos.alphaListDue(
            alphaAt, prefs.getLong(KEY_ALPHA_ATTEMPT, 0L), now, alpha.hasStoredSymbols() && CoinLogos.hasAlphaIds(saved?.alpha),
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
            mirrorAllowed = !CoinLogos.isFresh(prefs.getLong(KEY_META_ATTEMPT, 0L), now, CoinLogoStorage.MAP_RETRY_MILLIS),
            savedBinance = saved?.binance.orEmpty(),
        )
        val result = withContext(Dispatchers.IO) {
            CoinLists.fetch({ url -> listClient.callExchange(url, null, HttpBodyLimit.BULK_MAX_BYTES) }, request) { isNetworkError(it) }
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
            if (result.alphaSource == CoinLists.Source.DIRECT) alpha.storeFetched(symbols, now) else alpha.storeSymbols(symbols)
        }
        // Binance-Namen für «gleicher Token» (K-8): Spot vor Futures
        if (freshBinance != null) tokenIdentityStore.saveBinance(CoinMeta.binanceFullNames(freshBinance))
        val meta = CoinMeta.Meta(now, freshBinance ?: saved?.binance.orEmpty(), freshAlpha ?: saved?.alpha.orEmpty())
        lists = meta
        listsVersion++
        // Alpha-Paare von Binance und Namen für «α» sofort
        if (freshAlpha != null) alpha.publishTokens(freshAlpha)
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                listsFile.parentFile?.mkdirs()
                listsFile.writeText(CoinMeta.encode(meta))
            }.onFailure { Timber.w(it, "Binance-Listen nicht gespeichert") }
        }
        // Namen sofort (gespeicherte mit CoinGecko-Namen behalten Vorrang)
        names.publishListNames(CoinMeta.listNames(meta.binance, meta.alpha))
        Timber.i(
            "Binance-Listen: %d Einträge (%s), Alpha %d (%s), %d Namen",
            meta.binance.size, result.binanceSource, meta.alpha.size, result.alphaSource, names.size,
        )
        true
    }

    private fun isNetworkError(e: Exception): Boolean =
        e is NetworkError || e is TimeoutError || e is java.io.IOException

    // ---- 3. Abgleich im Hintergrund

    private val syncLock = Any()
    private var syncJob: Job? = null
    @Volatile private var rerunImages = false

    /**
     * Letzter Abgleich scheiterte am fehlenden Netz (z. B. kein Internet direkt nach der Installation):
     * [CoinLogoSync] holt ihn nach, sobald wieder Netz da ist ([CoinLogoRetry.syncNeeded]). Nur im
     * Speicher — nach einem Neustart der App läuft der Abgleich ohnehin beim Öffnen.
     */
    private val _retryPending = MutableStateFlow(false)
    val retryPending: StateFlow<Boolean> = _retryPending.asStateFlow()

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
                // Fehler im Abschluss (Widgets neu zeichnen) nur protokollieren
                runCatchingCancellable { onDone(added) }.onFailure { Timber.w(it, "Logo-Abgleich: Abschluss fehlgeschlagen") }
            }
        }
    }

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

    /**
     * Rangliste holen und alle fehlenden Logos laden. Die Bilder der grössten 1000 (Rangliste Seiten 1–4
     * mit Binance- und Alpha-Liste) beginnen schon, während die übrigen Seiten laden; danach nur noch,
     * was die übrigen Seiten dazubringen.
     */
    private suspend fun syncAll(): Int = coroutineScope {
        val early = CompletableDeferred<Map<String, String>>()
        val first = async { imageStore.downloadMissing(early.await()) }
        var usedEarly = false
        val current = try {
            symbolMap { partial ->
                usedEarly = true
                early.complete(CoinLogos.withStockLogos(partial, names.tradFiBases))
            }
        } catch (e: Throwable) {
            early.complete(emptyMap())
            throw e
        }
        // Aktien-Logos für alle TradFi-Kürzel ohne Eintrag dazu
        val wanted = CoinLogos.withStockLogos(current, names.tradFiBases)
        if (!usedEarly) early.complete(wanted)
        var result = first.await()
        if (usedEarly) result += imageStore.downloadMissing(wanted)
        _retryPending.value = listsNetworkFailed || mapNetworkFailed || result.network
        result.added
    }

    // ---- 2. Zuordnung Kürzel → Bild (Rangliste von CoinGecko)

    val mapMutex = Mutex()
    @Volatile var map: Map<String, String>? = null

    /** Letzter Abruf der Rangliste scheiterte am Netz (für [retryPending]). */
    @Volatile var mapNetworkFailed = false

    /** Zuordnung für die Start-Logos ([LogoImageStore.ensureStarterLogos]); bei Fehlern leer. */
    suspend fun symbolMapOrEmpty(): Map<String, String> = runCatchingCancellable { symbolMap() }.getOrDefault(emptyMap())

    /** Merkt sich, ob eine Quelle am Netz scheiterte (keine Verbindung, Zeitüberschreitung). */
    inner class NetState {
        @Volatile var failed = false
        fun note(e: Exception) {
            if (isNetworkError(e)) failed = true
        }
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

    companion object {
        // «v5»: Einträge der früheren GitHub-Liste fallen beim Lesen weg (isAllowedUrl), dann neu direkt
        const val KEY_MAP_TIME = "map_time_v5"
        const val KEY_MAP_ATTEMPT = "map_attempt_v5"
        /** Kennung der früheren GitHub-Liste (nicht mehr benutzt, wird gelöscht). */
        const val OLD_KEY_INDEX_ETAG = "index_etag_v5"
        const val KEY_ALPHA_ATTEMPT = "alpha_attempt_v1"
        const val MAP_FILE = "coin_logos/map5.txt"
        /** Zuordnung und Namen älterer Fassungen (gelesen bis zur ersten neuen Rangliste, dann gelöscht). */
        val OLD_FILES = listOf("coin_logos/map4.txt", "coin_logos/names4.txt")
        /** Zuletzt geholte Binance- und Alpha-Liste (Format wie `coin_meta.json`, [CoinMeta.encode]). */
        const val LISTS_FILE = "coin_logos/lists1.json"
        /** Zeitpunkt der zuletzt direkt geholten Binance-Symbolliste bzw. des letzten Versuchs. */
        const val KEY_BINANCE_TIME = "binance_list_time_v1"
        const val KEY_BINANCE_ATTEMPT = "binance_list_attempt_v1"
        /** Letzter unbrauchbarer Abruf der Ersatzdatei `coin_meta.json` (dann frühestens nach einer Stunde). */
        const val KEY_META_ATTEMPT = "coin_meta_attempt_v1"
        /** Zeitgrenze für Binance- und Alpha-Liste (wie die Paarlisten). */
        const val LIST_TIMEOUT_SECONDS = 60L
    }
}
