package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject

/** Poloniex Spot. API v3: https://api-docs.poloniex.com/spot/api/ */
class Poloniex : SimpleMarket(
    "Poloniex",
    "https://api.poloniex.com/markets",
    "https://api.poloniex.com/markets/%1\$s/ticker24h",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            if (item.optString("state") != "NORMAL") return@forEachJSONObject
            pairs.add(
                CurrencyPairInfo(
                    item.getString("baseCurrencyName"),
                    item.getString("quoteCurrencyName"),
                    item.getString("symbol"),
                )
            )
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}_${checkerInfo.currencyCounter}"

    override fun parseTicker(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo) {
        // Einzelabfrage liefert ein Objekt; zur Sicherheit auch ein Array annehmen.
        val trimmed = responseString.trimStart()
        val json = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
        read(json, ticker)
    }

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("close")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("quantity")
        ticker.volQuote = json.optDoubleNoData("amount")
        ticker.timestamp = json.optLong("ts")
        // ticker24h: open = Kurs vor 24 h; dailyChange (Bruchteil) nur als Ersatz.
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.optDouble("open"))
            ?: Change24h.fraction(json.optDouble("dailyChange"))
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.poloniex.com/markets/ticker24h"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val symbol = item.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[symbol] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
