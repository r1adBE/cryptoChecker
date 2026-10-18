package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.NetworkError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.logos.CoinLists
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    @param:ApplicationContext private val context: Context,
    httpClient: OkHttpClient,
    private val connectivity: ConnectivityMonitor,
    private val tokenIdentityStore: TokenIdentityStore,
    private val imageStore: LogoImageStore,
    private val names: CoinNames,
    private val alpha: AlphaTokens,
) {
    private val apiClient: OkHttpClient = httpClient

    /**
     * Binance- und Alpha-Liste (je mehrere MB): längere Zeitgrenze wie bei den Paarlisten — mit den
     * 20 s für Kurse brach die Alpha-Liste bei langsamem Netz ab und die Alpha-Kürzel kamen nie an.
     */
    private val listClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy { CoinLogoStorage.prefs(context) }
    private val mapFile by lazy { File(context.noBackupFilesDir, MAP_FILE) }
    private val listsFile by lazy { File(context.noBackupFilesDir, LISTS_FILE) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 1. Binance-Symbolliste und Alpha-Liste (Namen, Alpha-Kennzeichen) — vor allem anderen

    private val listsMutex = Mutex()

    /** Zuletzt bekannte Binance- und Alpha-Listen (direkt, Ersatzdatei oder gespeichert). */
    @Volatile private var lists: CoinMeta.Meta? = null
    @Volatile private var listsRead = false

    /** Stand der Listen; die Zuordnung übernimmt neue Listen auch ohne neue Rangliste ([withListsMerged]). */
    @Volatile private var listsVersion = 0
    @Volatile private var mergedListsVersion = -1

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

    private suspend fun savedLists(): CoinMeta.Meta? {
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
                onDone(added)
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

    private val mapMutex = Mutex()
    @Volatile private var map: Map<String, String>? = null

    /** Letzter Abruf der Rangliste scheiterte am Netz (für [retryPending]). */
    @Volatile private var mapNetworkFailed = false

    /** Zuordnung für die Start-Logos ([LogoImageStore.ensureStarterLogos]); bei Fehlern leer. */
    suspend fun symbolMapOrEmpty(): Map<String, String> = runCatchingCancellable { symbolMap() }.getOrDefault(emptyMap())

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
            CoinLogos.decode(CoinLogoStorage.readFirst(mapFile, File(context.noBackupFilesDir, OLD_FILES[0])))
        }.also { map = it }
        names.loadSavedNames()
        val saved = savedLists()
        mapNetworkFailed = false
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_TIME, 0L), now) && known.isNotEmpty()) return withListsMerged(known)
        // Letzter Versuch scheiterte vor Kurzem: nicht bei jedem Start erneut fragen
        if (CoinLogos.isFresh(prefs.getLong(KEY_MAP_ATTEMPT, 0L), now, CoinLogoStorage.MAP_RETRY_MILLIS)) return withListsMerged(known)

        val binance = saved?.binance?.takeIf { it.isNotEmpty() }
        val alphaList = saved?.alpha?.takeIf { it.isNotEmpty() }
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
            val partial = CoinMeta.merge(top, binance, alphaList).map
            handled += imageStore.dropChangedImages(known, partial)
            onTop(partial)
        }
        // Kleinere Coins (Rang 1001–2500) nur noch für die Lücken — und nur ohne getaktetes Netz (mobile
        // Daten): sonst gilt die Liste als unvollständig und wird nach einigen Stunden erneut versucht
        val extra = ArrayList<CoinMeta.GeckoRow>()
        var extraFetched = false
        if (complete) {
            if (CoinLogoStorage.isUnmetered(context)) {
                complete = fetchCoinGecko(extra, (CoinLogos.PAGES + 1)..(CoinLogos.PAGES + CoinLogos.EXTRA_PAGES), net)
                extraFetched = complete
            } else {
                complete = false
                Timber.i("Coin-Logos: getaktetes Netz, Zusatzseiten der Rangliste erst im WLAN")
            }
        }
        prefs.edit { putLong(KEY_MAP_ATTEMPT, now) }
        val merged = CoinMeta.merge(top, binance, alphaList, extra)
        // Kürzel nur aus den Zusatzseiten merken: ihre Logos lädt der Sammel-Abruf nur ohne getaktetes Netz
        if (extraFetched) imageStore.saveExtraOnly(CoinLogos.extraOnlyKeys(merged.map, CoinMeta.merge(top, binance, alphaList).map))
        // Unvollständig (eine Quelle fehlte): Bekanntes behalten und in einigen Stunden erneut
        val full = complete && binance != null && alphaList != null
        val fresh = if (full) merged.map else CoinLogos.withKnown(merged.map, known)
        val newNames = names.applyRankingNames(merged.names, full)
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                mapFile.parentFile?.mkdirs()
                mapFile.writeText(CoinLogos.encode(fresh))
                names.writeNamesFile(newNames)
                // Listen älterer Fassungen
                for (old in OLD_FILES) File(context.noBackupFilesDir, old).delete()
            }.onFailure { Timber.w(it, "Logo-Zuordnung nicht gespeichert") }
        }
        prefs.edit {
            putLong(KEY_MAP_TIME, CoinLogos.savedAtFor(now, full))
            remove(OLD_KEY_INDEX_ETAG)
        }
        // Geänderte Bild-Adressen (anderes Logo, andere Quelle): gespeichertes Bild neu laden
        imageStore.dropChangedImages(known, fresh, skip = handled)
        Timber.i("Coin-Logos: Liste mit %d Einträgen (%s)", fresh.size, if (full) "vollständig" else "unvollständig")
        map = fresh
        mergedListsVersion = listsVersion
        return fresh
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
            runCatchingCancellable {
                mapFile.parentFile?.mkdirs()
                mapFile.writeText(CoinLogos.encode(out))
            }.onFailure { Timber.w(it, "Logo-Zuordnung nicht gespeichert") }
        }
        return out
    }

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
