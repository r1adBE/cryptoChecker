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
import com.cryptochecker.marketdata.util.optText
import com.cryptochecker.marketdata.util.getText

/**
 * NonKYC.io. API v2 (https://api.nonkyc.io/api/v2). Die Marktliste nennt die
 * Paare „BASE/QUOTE“, Ticker erwarten „BASE_QUOTE“.
 */
class NonKyc : SimpleMarket(
    "NonKYC",
    "$BASE/market/getlist",
    "$BASE/ticker/%1\$s",
    "Non K Y C",
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            if (!(item.optBoolean("isActive") || item.optBoolean("active"))) return@forEachJSONObject
            val symbol = item.optString("symbol")
            val parts = symbol.split('/')
            if (parts.size != 2) return@forEachJSONObject
            val base = item.optString("primaryTicker").ifEmpty { parts[0] }
            pairs.add(CurrencyPairInfo(base, parts[1], "${parts[0]}_${parts[1]}"))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}_${checkerInfo.currencyCounter}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject, ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("last_price")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("base_volume")
        ticker.volQuote = json.optDoubleNoData("target_volume")
    }

    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? {
        jsonObject.optJSONObject("error")?.let { return it.getText("message") }
        return jsonObject.optText("error").ifEmpty { jsonObject.getText("message") }
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "$BASE/tickers"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val id = item.optString("ticker_id").ifEmpty {
                val base = item.optString("base_currency")
                val quote = item.optString("target_currency")
                if (base.isEmpty() || quote.isEmpty()) return@forEachJSONObject
                "${base}_$quote"
            }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[id] = ticker
        }
    }

    private companion object {
        const val BASE = "https://api.nonkyc.io/api/v2"
    }
}
