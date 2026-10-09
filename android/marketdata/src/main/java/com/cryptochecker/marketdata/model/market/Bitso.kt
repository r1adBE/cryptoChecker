package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TimeUtils
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject
import java.util.Locale

/** Bitso (Lateinamerika). API v3: https://docs.bitso.com */
class Bitso : SimpleMarket(
    "Bitso",
    "$BASE/available_books",
    "$BASE/ticker?book=%1\$s",
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("payload").forEachJSONObject { item ->
            val book = item.optString("book")
            val parts = book.split('_')
            if (parts.size != 2) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(parts[0].uppercase(Locale.ROOT), parts[1].uppercase(Locale.ROOT), book))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBaseLowerCase}_${checkerInfo.currencyCounterLowerCase}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONObject("payload"), ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleNoData("bid")
        ticker.ask = json.optDoubleNoData("ask")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.timestamp = runCatching { TimeUtils.convertISODateToTimestamp(json.optString("created_at")) }.getOrDefault(0L)
        // change_24 = absolute Veränderung der letzten 24 h
        ticker.change24hPercent = Change24h.fromAbsolute(ticker.last, json.optDouble("change_24"))
    }

    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? =
        jsonObject.getJSONObject("error").getString("message")

    // Ohne „book“ liefert der Endpunkt alle Bücher.
    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "$BASE/ticker"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONArray("payload").forEachJSONObject { item ->
            val book = item.optString("book").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[book] = ticker
        }
    }

    private companion object {
        const val BASE = "https://bitso.com/api/v3"
    }
}
