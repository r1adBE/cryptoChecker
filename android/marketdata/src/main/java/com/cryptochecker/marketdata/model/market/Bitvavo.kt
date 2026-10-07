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

/** Bitvavo (EU, EUR-Paare). API v2. */
class Bitvavo : SimpleMarket(
    "Bitvavo",
    "https://api.bitvavo.com/v2/markets",
    "https://api.bitvavo.com/v2/ticker/24h?market=%1\$s",
    errorPropertyName = "error"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            if (item.optString("status") != "trading") return@forEachJSONObject
            pairs.add(CurrencyPairInfo(item.getString("base"), item.getString("quote"), item.getString("market")))
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
        ticker.volQuote = json.optDoubleNoData("volumeQuote")
        ticker.timestamp = json.optLong("timestamp")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.bitvavo.com/v2/ticker/24h"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val market = item.optString("market").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[market] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
