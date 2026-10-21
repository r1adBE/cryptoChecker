// Ausweich-Kette des Wertverlaufs (Teil von `PortfolioHistorySource`): Coins, die Binance nicht führt (oder bei nicht
// erreichbarem Binance) und ohne eingetragene Börse, bekommen Tageskerzen von Coinbase → Kraken → OKX → Bybit — nur
// Börsen, die die App ohnehin anfragt, über deren eigene Kerzen. Erweiterungsfunktionen; Speicher in der Klasse.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import kotlinx.coroutines.CancellationException
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.OWN_CODEC
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.TTL_MILLIS
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.chainCacheName

/** Nach einer Abfrage ohne Treffer so lange nicht erneut alle Börsen fragen (nur im Speicher). */
private const val CHAIN_MISS_MILLIS = 60 * 60_000L

/**
 * Reihe der Ausweich-Kette für [coin] (in USDT, samt Weg = Paar der Quelle): Speicher, Gerät (12 h), sonst Abfrage. Neu
 * geholt wird nur mit aktuellem Kurs als Bezug ([chainReference]) — ohne Bezug ist der gleiche Token nicht bestätigt; dann
 * höchstens ein älterer Stand (den der Verlauf ohnehin noch gegen den aktuellen Kurs prüft). null = keine.
 */
internal suspend fun PortfolioHistorySource.chainOf(coin: String, span: Int): OwnCloses? {
    val now = System.currentTimeMillis()
    chainMemory[coin]?.let { (cached, at) ->
        if (cached.entry.span >= span && CycleCachePolicy.isFresh(at, now, TTL_MILLIS)) return cached
    }
    val stored = cacheStore.read(chainCacheName(coin), OWN_CODEC)
    if (stored != null && stored.value.entry.span >= span && CycleCachePolicy.isFresh(stored.savedAt, now, TTL_MILLIS)) {
        chainMemory[coin] = stored.value to stored.savedAt
        return stored.value
    }
    val older = stored?.value ?: chainMemory[coin]?.first
    chainMisses[coin]?.let { if (now - it in 0 until CHAIN_MISS_MILLIS) return older }
    val reference = chainReference(coin) ?: return older
    val fresh = fetchChain(coin, span, now, reference)
    if (fresh == null) {
        chainMisses[coin] = now
        return older
    }
    chainMisses.remove(coin)
    chainMemory[coin] = fresh to now
    cacheStore.write(chainCacheName(coin), fresh, now, OWN_CODEC)
    return fresh
}

/** Wie [chainOf], nur was schon da ist (Speicher, Gerät) — ohne Netz und ohne Altersgrenze (Widget). */
internal suspend fun PortfolioHistorySource.cachedChain(coin: String): OwnCloses? =
    chainMemory[coin]?.first
        ?: cacheStore.read(chainCacheName(coin), OWN_CODEC)?.also { chainMemory.putIfAbsent(coin, it.value to it.savedAt) }?.value

/**
 * Aktueller Kurs als Bezug der Identitätsprüfung: aus dem Speicher der Portfolio-Kurse, sonst der gemerkte Stand; nur
 * geprüfte Kurse ([CutoffExport.referencePrices]). Keine eigene Kursabfrage.
 */
internal suspend fun PortfolioHistorySource.chainReference(coin: String): Double? = try {
    // Ohne ungeprüfte und (seit Build 101) ohne «Kurs ungeprüft» — gleiche Regel wie die Exporte
    fun layer(p: PortfolioPrices) = CutoffExport.ReferenceLayer(p.prices, p.unchecked, p.unverified)
    CutoffExport.referenceOf(coin, listOf(layer(priceSource.cached(listOf(coin)))))
        ?: CutoffExport.referenceOf(coin, listOf(layer(priceSource.stored(listOf(coin)))))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Quellen der Kette der Reihe nach ([HistoricCloseChain.SERIES_CHAIN], Paare nach [HistoricCloseChain.pairs]): erste
 * Reihe mit passendem Namen ([HistoricCloseChain.nameAllowed]) und jüngstem Schluss ±25 % am aktuellen Kurs
 * ([HistoricCloseChain.acceptSeries]). USD-Kerzen durch USDT/USD des Tags wie bei eigenen Börsen ([fetchOwn]).
 */
private suspend fun PortfolioHistorySource.fetchChain(coin: String, span: Int, now: Long, reference: Double): OwnCloses? {
    val ownName = tokenIdentityStore.watchName(coin)
    val binanceName = tokenIdentityStore.binanceName(coin)
    for (source in HistoricCloseChain.SERIES_CHAIN) {
        val pairs = HistoricCloseChain.pairs(source, coin)
        val nameOk = HistoricCloseChain.nameAllowed(
            coin, own = false, marketKey = source, ownName = ownName,
            binanceName = binanceName, providerName = tokenIdentityStore.pairName(source, coin),
        )
        if (!nameOk) continue
        for (p in pairs) {
            val pair = chainCandlePair(source, coin, p)
            val entry = fetchOwn(pair, span, now) ?: continue
            val latest = entry.closes.maxByOrNull { it.key }?.value
            if (HistoricCloseChain.acceptSeries(latest, reference, nameOk = true)) return OwnCloses(pair.cacheKey, entry)
        }
    }
    return null
}

/** Anbieter einer Reihe der Kette aus ihrem Weg («Kraken|XBTUSD|NONE» → «Kraken»). */
internal fun chainProvider(route: String): String = HistoricCloseChain.provider(route.substringBefore('|'))

/** Anbieter der Kettenreihen für die Karte ([PortfolioDailyPrices.chainSources]); nur Coins, die im Ergebnis stehen. */
internal fun PortfolioDailyPrices.withChain(chain: Map<String, OwnCloses>): PortfolioDailyPrices {
    if (chain.isEmpty()) return this
    val sources = chain.filterKeys { it in closes }.mapValues { chainProvider(it.value.route) }
    return copy(chainSources = sources)
}
