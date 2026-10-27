// Funding-Verlauf für «Warum?» (Teil von `FuturesDataSource`, gespiegelt in `FuturesDataSource+FundingHistory.swift`):
// letzte abgerechnete Funding Rates mit Ausweich-Kette. Erweiterungsfunktionen; Client und Zwischenspeicher in der Klasse.
package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.FundingHistory
import com.cryptochecker.app.domain.activity.FundingPoint
import com.cryptochecker.app.domain.activity.FundingTrendRules
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import com.cryptochecker.app.data.remote.FuturesDataSource.Companion.BINANCE
import com.cryptochecker.app.data.remote.FuturesDataSource.Companion.BINANCE_COIN_M
import com.cryptochecker.app.data.remote.FuturesDataSource.Companion.BYBIT
import com.cryptochecker.app.data.remote.FuturesDataSource.Companion.OKX
import com.cryptochecker.app.data.remote.FuturesDataSource.Companion.HISTORY_CACHE_MILLIS
import com.cryptochecker.app.data.remote.FuturesDataSource.Companion.historyCache

/**
 * Die letzten [FundingTrendRules.LIMIT] abgerechneten Funding Rates — gleiche Ausweich-Kette
 * und Symbole wie [fetch] (Perpetual) bzw. [fetchForBase] (sonst), gesperrte Hosts übersprungen.
 * Einmal beim Öffnen von «Warum?»; Ergebnisse ~10 Min. im Speicher. null = keine Quelle.
 *  - Binance: `GET https://fapi.binance.com/fapi/v1/fundingRate?symbol=…&limit=4`
 *  - Bybit: `GET https://api.bybit.com/v5/market/funding/history?category=linear&symbol=…&limit=4`
 *  - OKX: `GET https://www.okx.com/api/v5/public/funding-rate-history?instId=…&limit=4`
 */
suspend fun FuturesDataSource.fundingHistory(watch: WatchEntity): FundingHistory? {
    val base = watch.baseAsset.trim().uppercase()
    val perpetual = watch.contractType.isPerpetual
    val key = if (perpetual) "${watch.marketKey}|${watch.pairId}|$base" else "SPOT|$base"
    val now = System.currentTimeMillis()
    historyCache[key]?.let { (time, value) -> if (now - time in 0 until HISTORY_CACHE_MILLIS) return value }
    val usdt = "${base}USDT"
    val chain: Array<Pair<String, suspend () -> FundingHistory>> = when (if (perpetual) watch.marketKey else null) {
        "BybitFutures" -> arrayOf(
            source(BYBIT) { bybitHistory(watch.pairId ?: usdt) },
            source(BINANCE) { binanceHistory(usdt) },
            source(OKX) { okxHistory("$base-USDT-SWAP") },
        )
        "BinanceFutures" -> if (FuturesSources.isCoinM(watch.pairId)) arrayOf(
            source(BINANCE_COIN_M) { binanceCoinMHistory(watch.pairId.orEmpty().removePrefix(FuturesSources.COIN_M_PREFIX)) },
            source(BINANCE) { binanceHistory(usdt) },
            source(BYBIT) { bybitHistory(usdt) },
            source(OKX) { okxHistory("$base-USDT-SWAP") },
        ) else arrayOf(
            source(BINANCE) {
                binanceHistory(watch.pairId?.takeIf { it.endsWith("USDT") || it.endsWith("USDC") } ?: usdt)
            },
            source(BYBIT) { bybitHistory(usdt) },
            source(OKX) { okxHistory("$base-USDT-SWAP") },
        )
        "OkexFutures" -> arrayOf(
            source(OKX) { okxHistory(watch.pairId?.takeIf { it.endsWith("-SWAP") } ?: "$base-USDT-SWAP") },
            source(BINANCE) { binanceHistory(usdt) },
            source(BYBIT) { bybitHistory(usdt) },
        )
        else -> arrayOf(
            source(BINANCE) { binanceHistory(usdt) },
            source(BYBIT) { bybitHistory(usdt) },
            source(OKX) { okxHistory("$base-USDT-SWAP") },
        )
    }
    val result = try {
        firstOf(*chain)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Timber.d(e, "Funding-Verlauf nicht verfügbar: %s", base)
        // Fehler nicht merken: beim nächsten Öffnen neu versuchen
        return null
    }
    historyCache[key] = now to result
    return result
}

suspend fun FuturesDataSource.binanceHistory(symbol: String): FundingHistory = history(
    FundingTrendRules.parseBinance(
        httpClient.callMarket("https://fapi.binance.com/fapi/v1/fundingRate?symbol=$symbol&limit=${FundingTrendRules.LIMIT}", null)
    ),
    "Binance Futures",
)

suspend fun FuturesDataSource.binanceCoinMHistory(symbol: String): FundingHistory = history(
    FundingTrendRules.parseBinance(
        httpClient.callMarket("https://dapi.binance.com/dapi/v1/fundingRate?symbol=$symbol&limit=${FundingTrendRules.LIMIT}", null)
    ),
    FuturesSources.BINANCE_COIN_M,
)

suspend fun FuturesDataSource.bybitHistory(symbol: String): FundingHistory = history(
    FundingTrendRules.parseBybit(
        httpClient.callMarket(
            "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=$symbol&limit=${FundingTrendRules.LIMIT}",
            null,
        )
    ),
    "Bybit",
)

suspend fun FuturesDataSource.okxHistory(instId: String): FundingHistory = history(
    FundingTrendRules.parseOkx(
        httpClient.callMarket(
            "https://www.okx.com/api/v5/public/funding-rate-history?instId=$instId&limit=${FundingTrendRules.LIMIT}",
            null,
        )
    ),
    "OKX",
)

/** Leere Antwort zählt als Fehler, damit die Kette mit der nächsten Quelle weitermacht. */
fun FuturesDataSource.history(points: List<FundingPoint>, source: String): FundingHistory {
    require(points.isNotEmpty()) { "Kein Funding-Verlauf von $source" }
    return FundingHistory(points, source)
}
