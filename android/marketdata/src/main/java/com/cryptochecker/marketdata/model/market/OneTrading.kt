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

/** One Trading (früher Bitpanda Pro), EU. Nur Spot. https://docs.onetrading.com */
class OneTrading : SimpleMarket(
    "One Trading",
    "https://api.onetrading.com/fast/v1/instruments",
    "https://api.onetrading.com/fast/v1/market-ticker/%1\$s",
    errorPropertyName = "error"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            if (item.optString("type") != "SPOT" || item.optString("state") != "ACTIVE") return@forEachJSONObject
            val base = item.optJSONObject("base")?.optString("code").orEmpty()
            val quote = item.optJSONObject("quote")?.optString("code").orEmpty()
            pairs.add(CurrencyPairInfo(base, quote, item.getString("id")))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}_${checkerInfo.currencyCounter}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject, ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("last_price")
        ticker.bid = json.optDoubleNoData("highest_bid")
        ticker.ask = json.optDoubleNoData("lowest_ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("base_volume")
        ticker.volQuote = json.optDoubleNoData("quote_volume")
        // Kein Zeitstempel in der Antwort → Abfragezeit
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.onetrading.com/fast/v1/market-ticker"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val id = item.optString("instrument_code").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[id] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
