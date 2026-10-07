package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject

/**
 * WOO X Spot. API v3: https://developer.woox.io
 *
 * Für Spot gibt es keinen 24-h-Ticker. Der Kurs kommt deshalb aus den letzten
 * 24 Stundenkerzen: Schluss der jüngsten Kerze = letzter Kurs, Hoch/Tief und
 * Volumen über alle 24 — so entspricht es einem gleitenden 24-h-Fenster.
 */
class Woo : WooBase("WOO X", "SPOT", FuturesContractType.NONE) {
    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String =
        "$BASE/v3/public/kline?symbol=${getPairId(checkerInfo)}&type=1h&limit=24"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        val rows = jsonObject.getJSONObject("data").getJSONArray("rows")
        var newest: JSONObject? = null
        var high = Double.NaN
        var low = Double.NaN
        var vol = 0.0
        var volQuote = 0.0
        rows.forEachJSONObject { row ->
            if (newest == null || row.optLong("startTimestamp") > newest!!.optLong("startTimestamp")) newest = row
            val h = row.optDouble("high")
            val l = row.optDouble("low")
            if (!h.isNaN()) high = if (high.isNaN()) h else maxOf(high, h)
            if (!l.isNaN()) low = if (low.isNaN()) l else minOf(low, l)
            vol += row.optDouble("volume", 0.0)
            volQuote += row.optDouble("amount", 0.0)
        }
        val last = newest ?: throw Exception("Keine Kerzen")
        ticker.last = last.getDouble("close")
        if (!high.isNaN()) ticker.high = high
        if (!low.isNaN()) ticker.low = low
        ticker.vol = vol
        ticker.volQuote = volQuote
        ticker.timestamp = jsonObject.optLong("timestamp")

        // Eröffnung der ältesten der 24 Stundenkerzen ≈ Kurs vor 24 h (nur mit vollem Fenster)
        val oldest = oldestRow(rows)
        ticker.change24hPercent =
            if (rows.length() >= 24 && oldest != null) Change24h.fromOpen(ticker.last, oldest.optDouble("open")) else null
    }

    private fun oldestRow(rows: JSONArray): JSONObject? {
        var oldest: JSONObject? = null
        rows.forEachJSONObject { row ->
            if (oldest == null || row.optLong("startTimestamp") < oldest!!.optLong("startTimestamp")) oldest = row
        }
        return oldest
    }
}

/** WOO X USDT-Perpetuals. */
class WooFutures : WooBase("WOO X Futures", "PERP", FuturesContractType.PERPETUAL) {
    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String =
        "$BASE/v3/public/futures?symbol=${getPairId(checkerInfo)}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        readFutures(jsonObject.getJSONObject("data").getJSONArray("rows").getJSONObject(0), ticker)
        ticker.timestamp = jsonObject.optLong("timestamp")
    }

    private fun readFutures(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("24hClose")
        ticker.high = json.optDoubleNoData("24hHigh")
        ticker.low = json.optDoubleNoData("24hLow")
        ticker.vol = json.optDoubleNoData("24hVolume")
        ticker.volQuote = json.optDoubleNoData("24hAmount")
        // 24hOpen = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.optDouble("24hOpen"))
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "$BASE/v3/public/futures"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        val json = JSONObject(responseString)
        val time = json.optLong("timestamp")
        json.getJSONObject("data").getJSONArray("rows").forEachJSONObject { row ->
            val symbol = row.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { readFutures(row, ticker) }.onFailure { return@forEachJSONObject }
            ticker.timestamp = time
            tickers[symbol] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}

abstract class WooBase(
    name: String,
    private val prefix: String,
    private val contractType: FuturesContractType,
) : SimpleMarket(name, "$BASE/v3/public/instruments", "", "Woo X", errorPropertyName = "message") {

    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONObject("data").getJSONArray("rows").forEachJSONObject { item ->
            if (item.optString("status") != "TRADING") return@forEachJSONObject
            val symbol = item.optString("symbol")
            // SPOT_BTC_USDT bzw. PERP_BTC_USDT
            val parts = symbol.split('_')
            if (parts.size != 3 || parts[0] != prefix) return@forEachJSONObject
            pairs.add(
                CurrencyPairInfo(
                    item.optString("baseAsset").ifEmpty { parts[1] },
                    item.optString("quoteAsset").ifEmpty { parts[2] },
                    symbol,
                    contractType,
                )
            )
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${prefix}_${checkerInfo.currencyBase}_${checkerInfo.currencyCounter}"

    protected companion object {
        const val BASE = "https://api.woox.io"
    }
}
