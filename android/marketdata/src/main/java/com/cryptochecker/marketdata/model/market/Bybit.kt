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

/** Bybit Spot. API v5: https://bybit-exchange.github.io/docs/v5/market/tickers */
class Bybit : BybitBase("Bybit", "spot", FuturesContractType.NONE)

/** Bybit USDT/USDC-Perpetuals (category=linear). */
class BybitFutures : BybitBase("Bybit Futures", "linear", FuturesContractType.PERPETUAL)

open class BybitBase(
    name: String,
    private val category: String,
    private val contractType: FuturesContractType,
) : SimpleMarket(
    name,
    // Linear hat über 500 Symbole; ohne limit kämen nur die ersten 500.
    "https://api.bybit.com/v5/market/instruments-info?category=$category&limit=1000",
    "https://api.bybit.com/v5/market/tickers?category=$category&symbol=%1\$s",
    errorPropertyName = "retMsg"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONObject("result").getJSONArray("list").forEachJSONObject { item ->
            if (item.optString("status") != "Trading") return@forEachJSONObject
            // Bei Futures nur Perpetuals; Laufzeit-Kontrakte haben andere Symbolnamen.
            if (contractType == FuturesContractType.PERPETUAL && item.optString("contractType") != "LinearPerpetual") {
                return@forEachJSONObject
            }
            pairs.add(CurrencyPairInfo(item.getString("baseCoin"), item.getString("quoteCoin"), item.getString("symbol"), contractType))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        readTicker(jsonObject.getJSONObject("result").getJSONArray("list").getJSONObject(0), ticker)
        ticker.timestamp = jsonObject.optLong("time")
    }

    private fun readTicker(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("lastPrice")
        ticker.bid = json.optDoubleNoData("bid1Price")
        ticker.ask = json.optDoubleNoData("ask1Price")
        ticker.high = json.optDoubleNoData("highPrice24h")
        ticker.low = json.optDoubleNoData("lowPrice24h")
        ticker.vol = json.optDoubleNoData("volume24h")
        ticker.volQuote = json.optDoubleNoData("turnover24h")
        // Gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(json.optDouble("price24hPcnt"))
    }

    override val bulkTickersNumOfRequests: Int get() = 1

    override fun getBulkTickersUrl(requestId: Int): String =
        "https://api.bybit.com/v5/market/tickers?category=$category"

    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        val json = JSONObject(responseString)
        val time = json.optLong("time")
        json.getJSONObject("result").getJSONArray("list").forEachJSONObject { item ->
            val symbol = item.optString("symbol").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { readTicker(item, ticker) }.onFailure { return@forEachJSONObject }
            ticker.timestamp = time
            tickers[symbol] = ticker
        }
    }

    /** Die Sammelabfrage liefert alle gehandelten Symbole der Kategorie. */
    override val bulkTickersComplete: Boolean get() = true
}
