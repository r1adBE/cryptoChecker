package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Bitstamp (EU-reguliert, auch EUR/GBP-Paare). API v2. */
class Bitstamp : SimpleMarket(
    "Bitstamp",
    "https://www.bitstamp.net/api/v2/trading-pairs-info/",
    "https://www.bitstamp.net/api/v2/ticker/%1\$s/",
    errorPropertyName = "reason"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            if (item.optString("trading") != "Enabled") return@forEachJSONObject
            val name = item.getString("name")                 // z. B. BTC/USD
            if ('/' !in name) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(name.substringBefore('/'), name.substringAfter('/'), item.getString("url_symbol")))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject, ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.timestamp = json.optLong("timestamp")
    }

    // Alle Ticker auf einmal; Kennung „BTC/USD“ → url_symbol „btcusd“.
    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://www.bitstamp.net/api/v2/ticker/"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val pair = item.optString("pair").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[pair.replace("/", "").lowercase(Locale.ROOT)] = ticker
        }
    }
}
