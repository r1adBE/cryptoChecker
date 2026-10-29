package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.BINANCE_HOST
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.REQUEST_TIMEOUT_MILLIS
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.STABLE
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.UNLISTED_RETRY_MILLIS
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.portfolio.BinanceBatchSplit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import timber.log.Timber
import java.net.URLEncoder

// Binance-Sammelabfrage der Portfolio-Kurse (Teil von [PortfolioPriceSource]): Filter auf die Binance-Coin-Liste, Halbieren
// bei HTTP 400 ([BinanceBatchSplit]). Erweiterungsfunktionen; der Zustand bleibt in der Klasse. Wie iOS
// `PortfolioPriceSource+Requests.swift` › `binanceBatch`.

/**
 * Kurse für [coins] beim Binance-Spiegel. Gefiltert nur auf eine Coin-Liste, die von Binance stammt
 * ([binanceCoinList], ohne Netz); ohne sie ungefiltert — unbekannte Symbole findet das Halbieren bei 400, ohne die
 * übrigen Coins um ihren Kurs zu bringen (auch bei der ersten Aktualisierung). Einzeln mit 400 abgelehnte Coins
 * ([BinanceBatchSplit.Outcome.unlisted]) fragt die Sammelabfrage [UNLISTED_RETRY_MILLIS] lang nicht wieder an.
 */
internal suspend fun PortfolioPriceSource.binanceBatch(coins: List<String>): BinanceBatchSplit.Outcome {
    if (BlockedSources.isBlocked(BINANCE_HOST)) return BinanceBatchSplit.Outcome(emptyMap(), true, emptySet(), 0)
    val now = System.currentTimeMillis()
    val known = binanceCoinList()
    val candidates = coins.filter { coin ->
        (known == null || coin in known) &&
            binanceUnlisted[coin]?.let { now - it in 0 until UNLISTED_RETRY_MILLIS } != true
    }
    val outcome = BinanceBatchSplit.fetch(candidates) { part -> tickerReply(part) }
    outcome.unlisted.forEach { binanceUnlisted[it] = now }
    return outcome
}

/** Eine Abfrage `ticker/price?symbols=[…]`: 400 = ein Symbol unbekannt; andere Fehler = gescheitert (451/403/429 vermerkt). */
private suspend fun PortfolioPriceSource.tickerReply(part: List<String>): BinanceBatchSplit.Reply {
    // Während des Halbierens gesperrt oder pausiert (403/451/429): nicht weiter anfragen
    if (BlockedSources.isBlocked(BINANCE_HOST)) return BinanceBatchSplit.Reply.Failed
    val bySymbol = part.associateBy { it + STABLE }
    val symbols = bySymbol.keys.joinToString(",", "[", "]") { "\"$it\"" }
    val url = "https://$BINANCE_HOST/api/v3/ticker/price?symbols=" + URLEncoder.encode(symbols, "UTF-8")
    val body = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callExchange(url, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 400 sperrt die Quelle nicht (BlockedSources nur 451/403 und 429/418)
        if (e is HttpExchangeError && e.httpCode == 400) return BinanceBatchSplit.Reply.Rejected
        if (!BlockedSources.noteFailure(BINANCE_HOST, e)) Timber.d(e, "Portfolio: Sammelabfrage fehlgeschlagen")
        null
    } ?: return BinanceBatchSplit.Reply.Failed
    val prices = HashMap<String, Double>()
    runCatchingCancellable {
        val array = JSONArray(body)
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val coin = bySymbol[o.optString("symbol")] ?: continue
            val price = o.optString("price").toDoubleOrNull()
            if (price != null && price > 0.0 && price.isFinite()) prices[coin] = price
        }
    }.onFailure { Timber.d(it, "Portfolio: Kurse nicht lesbar") }
    return BinanceBatchSplit.Reply.Prices(prices)
}
