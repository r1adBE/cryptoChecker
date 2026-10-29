// Zuordnung Kürzel → Bild aus der CoinGecko-Rangliste (Teil von `CoinListSync`): Datei bzw. frischer Abruf, Lücken
// aus den Binance-Listen, Seiten der Rangliste. Erweiterungsfunktionen; Sperre ([CoinListSync.symbolMap]) und Zustand in der Klasse.
package com.cryptochecker.app.data

import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.coroutines.sync.withLock
import com.cryptochecker.app.data.CoinListSync.Companion.KEY_MAP_ATTEMPT
import com.cryptochecker.app.data.CoinListSync.Companion.KEY_MAP_TIME
import com.cryptochecker.app.data.CoinListSync.NetState

/**
 * Zuordnung aus Datei bzw. frisch von CoinGecko, zusammengeführt mit den Binance-Listen aus
 * [refreshLists]; bei Fehlern die alte (auch abgelaufen). Nur freigegebene Bild-Adressen
 * ([CoinLogos.isAllowedUrl]).
 * [onTop]: Zuordnung nach den grössten 1000 (für den frühen Start der Bilder), nur bei neuem Abruf.
 */
suspend fun CoinListSync.symbolMap(onTop: (suspend (Map<String, String>) -> Unit)? = null): Map<String, String> =
    mapMutex.withLock { lockedSymbolMap(onTop) }

suspend fun CoinListSync.lockedSymbolMap(onTop: (suspend (Map<String, String>) -> Unit)?): Map<String, String> {
    val now = System.currentTimeMillis()
    val known = map ?: withContext(Dispatchers.IO) { CoinLogos.decode(CoinLogoStorage.readNonBlank(mapFile)) }
        .also { map = it }
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
        }.onFailure { Timber.w(it, "Logo-Zuordnung nicht gespeichert") }
    }
    prefs.edit { putLong(KEY_MAP_TIME, CoinLogos.savedAtFor(now, full)) }
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
suspend fun CoinListSync.withListsMerged(known: Map<String, String>): Map<String, String> {
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

/** Ersatz-Adressen je Kürzel aus den gespeicherten Binance-Listen ([CoinMeta.alternates]); ohne Listen leer. */
suspend fun CoinListSync.logoAlternates(): Map<String, List<String>> =
    savedLists()?.let { CoinMeta.alternates(it.binance, it.alpha) }.orEmpty()

/** Eine Seite der Rangliste; bei «429» einmal nach der verlangten Pause wiederholen. */
suspend fun CoinListSync.marketsPage(page: Int): String = try {
    apiClient.callExchange(CoinLogos.marketsUrl(page), null)
} catch (e: HttpExchangeError) {
    if (e.httpCode != 429) throw e
    delay(CoinLogos.rateLimitWaitMillis(e.retryAfterSeconds))
    apiClient.callExchange(CoinLogos.marketsUrl(page), null)
}

/** Seiten der Rangliste mit Pause dazwischen nach [out]; false = eine Seite fehlte (Abbruch). */
suspend fun CoinListSync.fetchCoinGecko(out: MutableList<CoinMeta.GeckoRow>, pages: IntRange, net: NetState): Boolean {
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
