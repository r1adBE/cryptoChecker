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

/**
 * LATOKEN. API v2: Paare und Ticker nennen die Währungen nur per ID (UUID);
 * die Kürzel kommen aus der Währungsliste. Paar-Kennung „<Basis-ID>/<Quote-ID>“.
 * Achtung: „amount24h“ ist das Volumen im Coin, „volume24h“ das im Gegenwert.
 */
class Latoken : SimpleMarket(
    "LATOKEN",
    "",
    "https://api.latoken.com/v2/ticker/%1\$s",
    "La token",
    errorPropertyName = "message"
) {
    override val currencyPairsNumOfRequests: Int get() = 2
    override val currencyPairsCombined: Boolean get() = true

    override fun getCurrencyPairsUrl(requestId: Int): String =
        if (requestId == 0) "https://api.latoken.com/v2/currency" else "https://api.latoken.com/v2/pair"

    override fun parseCurrencyPairsCombined(responses: List<String>, pairs: MutableList<CurrencyPairInfo>) {
        val tags = HashMap<String, String>()
        JSONArray(responses[0]).forEachJSONObject { currency ->
            val id = currency.optString("id")
            val tag = currency.optString("tag")
            if (id.isNotEmpty() && tag.isNotEmpty()) tags[id] = tag
        }
        JSONArray(responses[1]).forEachJSONObject { pair ->
            if (pair.optString("status") != "PAIR_STATUS_ACTIVE") return@forEachJSONObject
            val baseId = pair.optString("baseCurrency")
            val quoteId = pair.optString("quoteCurrency")
            val base = tags[baseId] ?: return@forEachJSONObject
            val quote = tags[quoteId] ?: return@forEachJSONObject
            pairs.add(CurrencyPairInfo(base, quote, "$baseId/$quoteId"))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}/${checkerInfo.currencyCounter}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject, ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("lastPrice")
        ticker.bid = json.optDoubleNoData("bestBid")
        ticker.ask = json.optDoubleNoData("bestAsk")
        ticker.vol = json.optDoubleNoData("amount24h")
        ticker.volQuote = json.optDoubleNoData("volume24h")
        ticker.timestamp = json.optLong("updateTimestamp")
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.latoken.com/v2/ticker"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val baseId = item.optString("baseCurrency")
            val quoteId = item.optString("quoteCurrency")
            if (baseId.isEmpty() || quoteId.isEmpty()) return@forEachJSONObject
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers["$baseId/$quoteId"] = ticker
        }
    }
}
