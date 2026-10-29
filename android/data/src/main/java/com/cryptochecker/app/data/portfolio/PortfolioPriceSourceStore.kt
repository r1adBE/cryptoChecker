package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.StoredPrice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.STABLE
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.STORE_FILE

// Letzter Stand der Portfolio-Kurse auf dem Gerät (Teil von [PortfolioPriceSource]): lesen, zusammenführen, schreiben,
// leeren. Erweiterungsfunktionen; der Zustand bleibt in der Klasse.

/**
 * Wie [cached], ergänzt um den letzten gemerkten Stand auf dem Gerät (einmal je Prozess
 * gelesen; je Coin gewinnt der neuere Kurs) — nur für die Anzeige im Portfolio-Tab nach einem
 * Neustart oder ohne Netz: lieber der letzte Wert mit «Stand …» als «0.00». `updatedAt` ist
 * dann alt; ob der Stand veraltet ist, entscheidet [PortfolioPriceFallback.state].
 */
suspend fun PortfolioPriceSource.stored(coins: Collection<String>): PortfolioPrices = withContext(Dispatchers.IO) {
    val merged = PortfolioPriceFallback.merge(currentEntries(), loadStored())
    // Gekapselte Coins ohne eigenen Kurs: Kurs der Basis, markiert ([PortfolioPrices.viaBase])
    val via = ChainAssets.priceViaBase(normalized(coins), merged.keys)
    val hits = normalized(coins).mapNotNull { coin -> (merged[coin] ?: via[coin]?.let { merged[it] })?.let { coin to it } }
    PortfolioPrices(
        prices = hits.associate { (coin, entry) -> coin to entry.price } + (STABLE to 1.0),
        updatedAt = hits.minOfOrNull { it.second.at } ?: 0L,
        unchecked = normalized(coins).filterTo(HashSet()) { it in uncheckedCoins && it !in merged && it !in via },
        noMarket = normalized(coins).filterTo(HashSet()) { it in noMarketCoins && it !in merged && it !in via },
        unavailable = normalized(coins).filterTo(HashSet()) { it in unavailableCoins && it !in merged && it !in uncheckedCoins && it !in via },
        viaBase = via,
        times = hits.associate { (coin, entry) -> coin to entry.at },
        sources = hits.mapNotNull { (coin, _) -> sourceOf[coin]?.let { coin to it } }.toMap(),
        exchangeFailed = hits.mapNotNull { (coin, _) -> failedOf[coin]?.let { coin to it } }.toMap(),
        mixedExchange = mixedCoins.filterTo(HashSet()) { coin -> hits.any { it.first == coin } },
        binance = hits.mapNotNull { (coin, _) -> binanceOf[coin]?.let { coin to it } }.toMap(),
        // «Kurs ungeprüft» aus dem gemerkten Stand (auch nach einem Neustart); gekapselte Coins erben ihn von der Basis
        unverified = PortfolioPriceFallback.unverified(normalized(coins), merged, via),
    )
}

/**
 * «Portfolio leeren»: gemerkte Kurse (nennen die gehaltenen Coins) und Zwischenspeicher weg. Eine gerade laufende
 * Abfrage übernimmt danach nichts mehr ([PortfolioPriceSource.clearGeneration]).
 */
suspend fun PortfolioPriceSource.clearStored() = withContext(Dispatchers.IO) {
    storeMutex.withLock {
        clearGeneration++
        cache.clear()
        uncheckedCoins.clear()
        noMarketCoins.clear()
        binanceUnlisted.clear()
        unverifiedCoins.clear()
        // Auch «Kurs nicht verfügbar» und die Börsen-Herkunft (wie iOS) — nennen gehaltene Coins
        unavailableCoins.clear()
        sourceOf.clear()
        failedOf.clear()
        binanceOf.clear()
        mixedCoins = emptySet()
        runCatchingCancellable { storeFile.delete() }
        storedEntries = emptyMap()
    }
}

/** Gemerkter Stand (einmal je Prozess aus der Datei gelesen). */
suspend fun PortfolioPriceSource.loadStored(): Map<String, StoredPrice> {
    storedEntries?.let { return it }
    return storeMutex.withLock {
        storedEntries ?: run {
            val text = runCatchingCancellable { if (storeFile.isFile) storeFile.readText() else null }
                .onFailure { Timber.d(it, "Portfolio: gemerkte Kurse unlesbar") }
                .getOrNull()
            PortfolioPriceFallback.prune(PortfolioPriceFallback.decode(text), System.currentTimeMillis())
                .also { storedEntries = it }
        }
    }
}

/**
 * Nach jedem erfolgreichen Abruf: gemerkter Stand und Zwischenspeicher (je Coin der neuere) als
 * letzten Stand schreiben. Datei in `noBackupFilesDir` — nie in einer Sicherung (wie die
 * Momentaufnahme des Portfolio-Widgets), denn sie nennt die gehaltenen Coins. Über eine
 * Hilfsdatei und Umbenennen geschrieben. Nicht mehr, wenn seit Beginn der Abfrage ([generation]) geleert wurde.
 */
suspend fun PortfolioPriceSource.persist(generation: Int) {
    val stored = loadStored()
    storeMutex.withLock {
        if (generation != clearGeneration) return@withLock
        val entries = PortfolioPriceFallback.prune(
            PortfolioPriceFallback.merge(currentEntries(), storedEntries ?: stored),
            System.currentTimeMillis(),
        )
        storedEntries = entries
        runCatchingCancellable {
            val tmp = File(storeFile.parentFile, "$STORE_FILE.tmp")
            tmp.writeText(PortfolioPriceFallback.encode(entries))
            if (!tmp.renameTo(storeFile)) {
                storeFile.delete()
                tmp.renameTo(storeFile)
            }
        }.onFailure { Timber.d(it, "Portfolio: Kurse nicht gemerkt") }
    }
}

/** Zwischenspeicher als gemerkte Kurse; «Kurs ungeprüft» wird mitgemerkt ([StoredPrice.unverified]). */
fun PortfolioPriceSource.currentEntries(): Map<String, StoredPrice> =
    cache.entries.associate { (coin, entry) -> coin to StoredPrice(entry.first, entry.second, coin in unverifiedCoins) }
