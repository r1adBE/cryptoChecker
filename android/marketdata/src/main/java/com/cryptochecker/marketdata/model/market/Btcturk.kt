package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject

/** BtcTurk (Türkei). API v2: https://docs.btcturk.com */
class Btcturk : SimpleMarket(
    "BtcTurk",
    "https://api.btcturk.com/api/v2/server/exchangeinfo",
    "https://api.btcturk.com/api/v2/ticker?pairSymbol=%1\$s",
    "B T C Turk",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONObject("data").getJSONArray("symbols").forEachJSONObject { item ->
            if (item.optString("status") != "TRADING") return@forEachJSONObject
            pairs.add(CurrencyPairInfo(item.getString("numerator"), item.getString("denominator"), item.getString("name")))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}${checkerInfo.currencyCounter}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONArray("data").getJSONObject(0), ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.timestamp = json.optLong("timestamp")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.btcturk.com/api/v2/ticker"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONArray("data").forEachJSONObject { item ->
            val pair = item.optString("pair").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[pair] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
