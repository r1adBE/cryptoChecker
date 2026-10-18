// Vorschau eines Coins aus dem Markt-Tab (Teil von `InfoViewModel`): Paar für die Vorschau suchen, Kurs
// holen und «Was bewegt sich mit?» erklären.
// Eigene Datei, weil die Vorschau ein nicht gespeichertes Paar betrifft und mit den Markt-Karten nichts teilt.
package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Daten für «Was bewegt sich mit?»; null, wenn gar nichts geladen werden konnte. */
suspend fun InfoViewModel.explain(watch: WatchEntity): WhyReport? =
    runCatching { activityMonitor.explain(watch) }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        .getOrNull()

/**
 * «Heute auffällig» → Coin nicht in der Merkliste: Vorschau-Paar «COIN/USDT» auf Binance Spot
 * — nur im Speicher ([PREVIEW_WATCH_ID]), nichts wird gespeichert, bis der Nutzer es hinzufügt.
 * null, wenn es Binance nicht (mehr) gibt oder die gespeicherte Paarliste das Spot-Paar nicht
 * führt; dann wie bisher die Suche im Hinzufügen-Tab. (Die Zeilen kommen ohnehin aus den
 * Binance-Tickern «…USDT», die Prüfung ist nur die Absicherung.) Wie iOS `CycleViewModel.previewWatch`.
 */
suspend fun InfoViewModel.previewWatch(symbol: String): WatchEntity? {
    val base = symbol.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
    val key = com.cryptochecker.app.domain.starter.StarterPairs.BINANCE_KEY
    val name = com.cryptochecker.marketdata.config.MarketsConfig.MARKETS[key]?.name ?: return null
    val market = com.cryptochecker.app.domain.model.MarketInfo(key, name)
    val pairs = runCatching { marketRepository.getMarketCurrencyPairsInfo(market).pairs }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        .getOrDefault(emptyList())
    var pairId = base + PREVIEW_QUOTE
    // Leere Liste (noch nie geladen): den Ticker selbst entscheiden lassen
    if (pairs.isNotEmpty()) {
        val listed = pairs.firstOrNull {
            it.contractType == FuturesContractType.NONE &&
                it.base.equals(base, ignoreCase = true) &&
                it.quote.equals(PREVIEW_QUOTE, ignoreCase = true)
        } ?: return null
        listed.pairId?.let { pairId = it }
    }
    return WatchEntity(
        id = PREVIEW_WATCH_ID,
        marketKey = key,
        marketName = name,
        baseAsset = base,
        quoteAsset = PREVIEW_QUOTE,
        pairId = pairId,
    )
}

/**
 * Kurs und rollende 24-h-Veränderung für das Vorschau-Paar — eine Ticker-Abfrage wie beim
 * Aktualisieren eines Paars, aber ohne Speichern, Alarme oder Meldungen. Fehler stehen in
 * [WatchEntity.lastError] (die Oberfläche übersetzt sie wie in der Merkliste).
 */
suspend fun InfoViewModel.previewQuote(watch: WatchEntity): WatchEntity {
    val result = runCatchingCancellable {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            marketRepository.getMarketTicker(
                com.cryptochecker.app.domain.model.MarketInfo(watch.marketKey, watch.marketName),
                com.cryptochecker.marketdata.model.MarketPair(
                    watch.baseAsset, watch.quoteAsset, watch.pairId, watch.contractType
                ),
            )
        }
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
    val last = result?.ticker?.last?.takeIf { it.isFinite() && it > 0.0 }
    return if (last != null) {
        watch.copy(
            lastPrice = last,
            change24h = result.ticker.change24hPercent?.takeIf { it.isFinite() },
            lastUpdate = System.currentTimeMillis(),
            lastError = null,
        )
    } else {
        watch.copy(
            lastError = result?.error
                ?: com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError.NO_TICKER_DATA,
        )
    }
}

/** Quote des Vorschau-Paars (Binance Spot «COIN/USDT», wie die Start-Merkliste). */
private const val PREVIEW_QUOTE = "USDT"
