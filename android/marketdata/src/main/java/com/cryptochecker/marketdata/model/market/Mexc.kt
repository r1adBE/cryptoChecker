package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.optStrings
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject

/** MEXC Spot. Schnittstelle weitgehend wie Binance v3. */
class Mexc : SimpleMarket(
    "MEXC",
    "https://api.mexc.com/api/v3/exchangeInfo",
    "https://api.mexc.com/api/v3/ticker/24hr?symbol=%1\$s",
    errorPropertyName = "msg"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("symbols").forEachJSONObject { item ->
            // Status "1" = handelbar (ältere Antworten: "ENABLED").
            val status = item.optString("status")
            if (status != "1" && status != "ENABLED" && status != "TRADING") return@forEachJSONObject
            if (!item.optBoolean("isSpotTradingAllowed", true)) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(item.getString("baseAsset"), item.getString("quoteAsset"), item.getString("symbol")))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject, ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("lastPrice")
        ticker.bid = json.optDoubleNoData("bidPrice")
        ticker.ask = json.optDoubleNoData("askPrice")
        ticker.high = json.optDoubleNoData("highPrice")
        ticker.low = json.optDoubleNoData("lowPrice")
        ticker.vol = json.optDoubleNoData("volume")
        ticker.volQuote = json.optDoubleNoData("quoteVolume")
        ticker.timestamp = json.optLong("closeTime")
        // openPrice = Kurs vor 24 h; priceChangePercent ist bei MEXC ein Bruchteil.
        ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.optDouble("openPrice"))
            ?: Change24h.fraction(json.optDouble("priceChangePercent"))
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.mexc.com/api/v3/ticker/24hr"
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

/** MEXC USDT-Perpetuals (contract.mexc.com). */
class MexcFutures : SimpleMarket(
    "MEXC Futures",
    "https://contract.mexc.com/api/v1/contract/detail",
    "https://contract.mexc.com/api/v1/contract/ticker?symbol=%1\$s",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONArray("data").forEachJSONObject { item ->
            // state 0 = aktiv; futureType 1 = Perpetual
            if (item.optInt("state", -1) != 0) return@forEachJSONObject
            if (item.optInt("futureType", 1) != 1) return@forEachJSONObject
            val tradFi = TradFi.mexc(item.optStrings("conceptPlate"), item.optInt("type", 1))
            pairs.add(
                CurrencyPairInfo(item.getString("baseCoin"), item.getString("quoteCoin"), item.getString("symbol"), FuturesContractType.PERPETUAL, tradFi)
            )
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONObject("data"), ticker)

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("lastPrice")
        ticker.bid = json.optDoubleNoData("bid1")
        ticker.ask = json.optDoubleNoData("ask1")
        ticker.high = json.optDoubleNoData("high24Price")
        ticker.low = json.optDoubleNoData("lower24Price")
        // volume24 zählt Kontrakte, nicht Coins — deshalb nur das Quote-Volumen.
        ticker.vol = Ticker.NO_DATA.toDouble()
        ticker.volQuote = json.optDoubleNoData("amount24")
        ticker.timestamp = json.optLong("timestamp")
        // riseFallRate = gleitende 24 h als Bruchteil (die Tageswerte stehen in riseFallRates)
        ticker.change24hPercent = Change24h.fraction(json.optDouble("riseFallRate"))
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://contract.mexc.com/api/v1/contract/ticker"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONArray("data").forEachJSONObject { item ->
            val symbol = item.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[symbol] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
