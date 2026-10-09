package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject
import java.util.Locale

/** ZebPay (Indien), Spot-API v2. https://github.com/zebpay/zebpay-api-references */
class ZebPay : SimpleMarket(
    "ZebPay",
    "https://sapi.zebpay.com/api/v2/ex/exchangeInfo",
    "https://sapi.zebpay.com/api/v2/market/ticker?symbol=%1\$s",
    "Zeb Pay",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONObject("data").getJSONArray("symbols").forEachJSONObject { item ->
            if (item.optString("status").lowercase(Locale.ROOT) in CLOSED) return@forEachJSONObject
            if (item.has("enableTrading") && !item.optBoolean("enableTrading", true)) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(item.getString("baseAsset"), item.getString("quoteAsset"), item.getString("symbol")))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}-${checkerInfo.currencyCounter}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONObject("data"), ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("baseVolume")
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        ticker.timestamp = json.optLong("timestamp")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://sapi.zebpay.com/api/v2/market/allTickers"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONArray("data").forEachJSONObject { item ->
            val symbol = item.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[symbol] = ticker
        }
    }

    private companion object {
        val CLOSED = setOf("closed", "close", "halt", "halted", "suspended", "delisted", "inactive", "disabled")
    }
}
