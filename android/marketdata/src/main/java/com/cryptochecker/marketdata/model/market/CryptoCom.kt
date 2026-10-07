package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONObject

/** Crypto.com Exchange Spot. API v1; Ticker-Felder sind Einzelbuchstaben. */
class CryptoCom : SimpleMarket(
    "Crypto.com",
    "https://api.crypto.com/exchange/v1/public/get-instruments",
    "https://api.crypto.com/exchange/v1/public/get-tickers?instrument_name=%1\$s",
    errorPropertyName = "message"
) {
    override fun parseCurrencyPairsFromJsonObject(requestId: Int, jsonObject: JSONObject, pairs: MutableList<CurrencyPairInfo>) {
        jsonObject.getJSONObject("result").getJSONArray("data").forEachJSONObject { item ->
            if (item.optString("inst_type") != "CCY_PAIR" || !item.optBoolean("tradable")) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(item.getString("base_ccy"), item.getString("quote_ccy"), item.getString("symbol")))
        }
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
        read(jsonObject.getJSONObject("result").getJSONArray("data").getJSONObject(0), ticker)

    /** a = letzter Kurs, b/k = Geld/Brief, h/l = Hoch/Tief, v = Menge, vv = Wert, t = Zeit */
    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("a")
        ticker.bid = json.optDoubleNoData("b")
        ticker.ask = json.optDoubleNoData("k")
        ticker.high = json.optDoubleNoData("h")
        ticker.low = json.optDoubleNoData("l")
        ticker.vol = json.optDoubleNoData("v")
        ticker.volQuote = json.optDoubleNoData("vv")
        ticker.timestamp = json.optLong("t")
        // c = gleitende 24 h als Bruchteil (0.0583 = +5,83 %), null ohne Handel
        ticker.change24hPercent = Change24h.fraction(json.optDouble("c"))
    }

    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api.crypto.com/exchange/v1/public/get-tickers"
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONObject(responseString).getJSONObject("result").getJSONArray("data").forEachJSONObject { item ->
            val id = item.optString("i").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[id] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true
}
