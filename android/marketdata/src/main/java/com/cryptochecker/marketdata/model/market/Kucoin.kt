package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONObject

class Kucoin : SimpleMarket(
    "KuCoin",
    "https://api.kucoin.com/api/v2/symbols",
    "https://api.kucoin.com/api/v1/market/stats?symbol=%1\$s"
) {

    @Throws(Exception::class)
    override fun parseTickerFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        ticker: Ticker,
        checkerInfo: CheckerInfo
    ) {
        val data = jsonObject.getJSONObject("data")
        readTicker(data, ticker)
        ticker.timestamp = data.optLong("time")
    }

    /** Einzelabruf und Massenabfrage tragen dieselben Feldnamen. */
    @Throws(Exception::class)
    private fun readTicker(json: JSONObject, ticker: Ticker) {
        ticker.bid = json.optDouble("buy", Ticker.NO_DATA.toDouble())
        ticker.ask = json.optDouble("sell", Ticker.NO_DATA.toDouble())

        ticker.vol = json.optDouble("vol", Ticker.NO_DATA.toDouble())
        ticker.volQuote = json.optDouble("volValue", Ticker.NO_DATA.toDouble())

        ticker.high = json.optDouble("high", Ticker.NO_DATA.toDouble())
        ticker.low = json.optDouble("low", Ticker.NO_DATA.toDouble())

        ticker.last = json.optDouble("last", Ticker.NO_DATA.toDouble())

        // changeRate = gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(json.optDouble("changeRate"))
    }

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(requestId: Int): String = ALL_TICKERS_URL

    @Throws(Exception::class)
    override fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        val data = JSONObject(responseString).getJSONObject("data")
        val time = data.optLong("time")

        data.getJSONArray("ticker").forEachJSONObject { entry ->
            val symbol = entry.optString("symbol").ifEmpty { return@forEachJSONObject }

            val ticker = SimpleTicker()
            readTicker(entry, ticker)
            ticker.timestamp = time
            tickers[symbol] = ticker
        }
    }

    private companion object {
        const val ALL_TICKERS_URL = "https://api.kucoin.com/api/v1/market/allTickers"
    }

    // ====================
    // Get currency pairs
    // ====================
    @Throws(Exception::class)
    override fun parseCurrencyPairsFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        pairs: MutableList<CurrencyPairInfo>
    ) {
        jsonObject
            .getJSONArray("data")
            .forEachJSONObject { symbolData ->
                if (symbolData.getBoolean("enableTrading")) {
                    pairs.add(
                        CurrencyPairInfo(
                            symbolData.getString("baseCurrency"),
                            symbolData.getString("quoteCurrency"),
                            symbolData.getString("symbol")
                        )
                    )
                }
            }
    }
}