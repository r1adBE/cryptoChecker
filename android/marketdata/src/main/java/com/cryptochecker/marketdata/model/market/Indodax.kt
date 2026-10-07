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

/**
 * Indodax (Indonesien). Achtung: In der Paarliste ist „base_currency“ der
 * Gegenwert (IDR/USDT) und „traded_currency“ der gehandelte Coin.
 * https://github.com/btcid/indodax-official-api-docs
 */
class Indodax : SimpleMarket(
    "Indodax",
    "https://indodax.com/api/pairs",
    "https://indodax.com/api/ticker/%1\$s",
    errorPropertyName = "error_description"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            fun flag(name: String) = item.opt(name).let { it == true || it == 1 || it == "1" || it == "true" }
            if (flag("is_maintenance") || flag("is_market_suspended")) return@forEachJSONObject
            pairs.add(
                CurrencyPairInfo(
                    item.getString("traded_currency").uppercase(Locale.ROOT),
                    item.getString("base_currency").uppercase(Locale.ROOT),
                    item.getString("id"),
                )
            )
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBaseLowerCase}${checkerInfo.currencyCounterLowerCase}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONObject("ticker"), checkerInfo.currencyBaseLowerCase, checkerInfo.currencyCounterLowerCase, ticker)

    private fun read(json: JSONObject, base: String, quote: String, ticker: Ticker) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleNoData("buy")
        ticker.ask = json.optDoubleNoData("sell")
        ticker.high = json.optDoubleNoData("high")
        ticker.low = json.optDoubleNoData("low")
        ticker.vol = json.optDoubleNoData("vol_$base")
        ticker.volQuote = json.optDoubleNoData("vol_$quote")
        ticker.timestamp = json.optLong("server_time")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://indodax.com/api/ticker_all"

    /** Schlüssel „btc_idr“ → Paar-Kennung „btcidr“. */
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        val all = JSONObject(responseString).getJSONObject("tickers")
        val keys = all.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parts = key.split('_')
            val item = all.optJSONObject(key) ?: continue
            if (parts.size != 2) continue
            val ticker = SimpleTicker()
            if (runCatching { read(item, parts[0], parts[1], ticker) }.isFailure) continue
            tickers[parts[0] + parts[1]] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
