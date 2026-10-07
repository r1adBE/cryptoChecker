package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONArray
import org.json.JSONObject

class GateIo : SimpleMarket(
    "Gate.io",
    "https://api.gateio.ws/api/v4/spot/currency_pairs",
    "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=%1\$s",
    "Gate io",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString)
            .forEachJSONObject { pairJson ->
                if(pairJson.getString("trade_status") == "tradable") {
                    pairs.add(
                        CurrencyPairInfo(
                            pairJson.getString("base"),
                            pairJson.getString("quote"),
                            pairJson.getString("id")
                        )
                    )
                }
            }
    }

    override fun parseTicker(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo) {
        val jsonArray = JSONArray(responseString)
        if(jsonArray.length() < 1) throw MarketParseException("No data")

        readTicker(jsonArray.getJSONObject(0), ticker)
    }

    /** Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur. */
    @Throws(Exception::class)
    private fun readTicker(json: JSONObject, ticker: Ticker) {
        ticker.bid = json.getDouble("highest_bid")
        ticker.ask = json.getDouble("lowest_ask")

        ticker.vol = json.getDouble("base_volume")
        ticker.volQuote = json.getDouble("quote_volume")

        ticker.high = json.getDouble("high_24h")
        ticker.low = json.getDouble("low_24h")
        ticker.last = json.getDouble("last")
    }

    /** Ohne currency_pair-Parameter liefert der Endpunkt alle Paare. */
    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(requestId: Int): String = ALL_TICKERS_URL

    @Throws(Exception::class)
    override fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        JSONArray(responseString).forEachJSONObject { entry ->
            val pairId = entry.optString("currency_pair").ifEmpty { return@forEachJSONObject }

            val ticker = SimpleTicker()
            readTicker(entry, ticker)
            tickers[pairId] = ticker
        }
    }

    private companion object {
        const val ALL_TICKERS_URL = "https://api.gateio.ws/api/v4/spot/tickers"
    }
}