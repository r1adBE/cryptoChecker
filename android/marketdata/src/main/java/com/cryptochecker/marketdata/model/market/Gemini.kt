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
 * Gemini Spot. Die Symbolliste enthält nur zusammengeschriebene Namen
 * (z. B. „btcusd“, „aavegusd“); Basis und Quote werden über bekannte
 * Quote-Endungen getrennt. Perpetuals („…perp“) sind ausgenommen.
 */
class Gemini : SimpleMarket(
    "Gemini",
    "https://api.gemini.com/v1/symbols",
    "https://api.gemini.com/v1/pubticker/%1\$s",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        val list = JSONArray(responseString)
        for (i in 0 until list.length()) {
            val symbol = list.getString(i).lowercase(Locale.ROOT)
            if (symbol.endsWith("perp")) continue
            val quote = QUOTES.firstOrNull { symbol.endsWith(it) && symbol.length > it.length } ?: continue
            val base = symbol.removeSuffix(quote)
            pairs.add(CurrencyPairInfo(base.uppercase(Locale.ROOT), quote.uppercase(Locale.ROOT), symbol))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        ticker.last = jsonObject.getDouble("last")
        ticker.bid = jsonObject.optDoubleNoData("bid")
        ticker.ask = jsonObject.optDoubleNoData("ask")
        // volume = { "BTC": "...", "USD": "...", "timestamp": ... }
        jsonObject.optJSONObject("volume")?.let { volume ->
            ticker.vol = volume.optDoubleNoData(checkerInfo.currencyBase.uppercase(Locale.ROOT))
            ticker.volQuote = volume.optDoubleNoData(checkerInfo.currencyCounter.uppercase(Locale.ROOT))
            ticker.timestamp = volume.optLong("timestamp")
        }
    }

    // Preisliste aller Paare — nur der letzte Kurs, das genügt für die Watchlist.
    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.gemini.com/v1/pricefeed"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val pair = item.optString("pair").ifEmpty { return@forEachJSONObject }
            val price = item.optDouble("price", Double.NaN)
            if (price.isNaN()) return@forEachJSONObject
            tickers[pair.lowercase(Locale.ROOT)] = SimpleTicker().apply { last = price }
        }
    }
    override val bulkTickersComplete: Boolean get() = true

    private companion object {
        /** Längste zuerst, damit „gusd“ vor „usd“ greift. */
        val QUOTES = listOf("rlusd", "gusd", "usdc", "usdt", "usd", "eur", "gbp", "sgd", "btc", "eth", "sol", "fil")
    }
}
