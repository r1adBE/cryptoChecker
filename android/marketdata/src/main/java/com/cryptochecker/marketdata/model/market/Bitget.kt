package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject

/** Bitget Spot. API v2. */
class Bitget : SimpleMarket(
    "Bitget",
    "https://api.bitget.com/api/v2/spot/public/symbols",
    "https://api.bitget.com/api/v2/spot/market/tickers?symbol=%1\$s",
    errorPropertyName = "msg"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("data").forEachJSONObject { item ->
            if (item.optString("status") != "online") return@forEachJSONObject
            pairs.add(CurrencyPairInfo(item.getString("baseCoin"), item.getString("quoteCoin"), item.getString("symbol")))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        BitgetTicker.read(jsonObject.getJSONArray("data").getJSONObject(0), ticker)
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.bitget.com/api/v2/spot/market/tickers"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) =
        BitgetTicker.readAll(responseString, tickers)
    override val bulkTickersComplete: Boolean get() = true
}

/** Bitget USDT-Perpetuals. */
class BitgetFutures : SimpleMarket(
    "Bitget Futures",
    "https://api.bitget.com/api/v2/mix/market/contracts?productType=USDT-FUTURES",
    "https://api.bitget.com/api/v2/mix/market/ticker?symbol=%1\$s&productType=USDT-FUTURES",
    errorPropertyName = "msg"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("data").forEachJSONObject { item ->
            if (item.optString("symbolType") != "perpetual" || item.optString("symbolStatus") != "normal") return@forEachJSONObject
            pairs.add(
                CurrencyPairInfo(item.getString("baseCoin"), item.getString("quoteCoin"), item.getString("symbol"), FuturesContractType.PERPETUAL)
            )
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        BitgetTicker.read(jsonObject.getJSONArray("data").getJSONObject(0), ticker)
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String =
        "https://api.bitget.com/api/v2/mix/market/tickers?productType=USDT-FUTURES"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) =
        BitgetTicker.readAll(responseString, tickers)
    override val bulkTickersComplete: Boolean get() = true
}

/** Spot und Futures liefern dieselben Feldnamen. */
internal object BitgetTicker {
    fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("lastPr")
        ticker.bid = json.optDoubleNoData("bidPr")
        ticker.ask = json.optDoubleNoData("askPr")
        ticker.high = json.optDoubleNoData("high24h")
        ticker.low = json.optDoubleNoData("low24h")
        ticker.vol = json.optDoubleNoData("baseVolume")
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        ticker.timestamp = json.optLong("ts")
        // change24h = gleitende 24 h als Bruchteil; changeUtc24h (seit 0 Uhr UTC) bleibt weg.
        ticker.change24hPercent = Change24h.fraction(json.optDouble("change24h"))
    }

    fun readAll(responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONArray("data").forEachJSONObject { item ->
            val symbol = item.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[symbol] = ticker
        }
    }
}
