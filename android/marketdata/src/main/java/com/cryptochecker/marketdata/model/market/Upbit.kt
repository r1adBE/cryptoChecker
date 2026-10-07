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

/** Upbit (Korea). Paare im Format QUOTE-BASE, z. B. KRW-BTC. https://docs.upbit.com */
class Upbit : UpbitStyleMarket(
    "Upbit",
    "Up bit",
    "https://api.upbit.com/v1",
    pairsQuery = "is_details=false",
    allTickersUrl = "https://api.upbit.com/v1/ticker/all?quote_currencies=KRW,BTC,USDT",
)

/** Bithumb (Korea), API v1 im selben Aufbau wie Upbit. https://apidocs.bithumb.com */
class Bithumb : UpbitStyleMarket(
    "Bithumb",
    "Bithumb",
    "https://api.bithumb.com/v1",
    pairsQuery = "isDetails=false",
    allTickersUrl = null,
)

abstract class UpbitStyleMarket(
    name: String,
    ttsName: String,
    private val base: String,
    pairsQuery: String,
    private val allTickersUrl: String?,
) : SimpleMarket(name, "$base/market/all?$pairsQuery", "$base/ticker?markets=%1\$s", ttsName) {

    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val market = item.optString("market")
            val parts = market.split('-')
            if (parts.size != 2) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(parts[1], parts[0], market))
        }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String =
        checkerInfo.currencyPairId ?: "${checkerInfo.currencyCounter}-${checkerInfo.currencyBase}"

    override fun parseTicker(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo) {
        read(JSONArray(responseString).getJSONObject(0), ticker)
    }

    private fun read(json: JSONObject, ticker: Ticker) {
        ticker.last = json.getDouble("trade_price")
        ticker.high = json.optDoubleNoData("high_price")
        ticker.low = json.optDoubleNoData("low_price")
        ticker.vol = json.optDoubleNoData("acc_trade_volume_24h")
        ticker.volQuote = json.optDoubleNoData("acc_trade_price_24h")
        ticker.timestamp = json.optLong("timestamp")
        // Kein 24-h-Wert: signed_change_rate bezieht sich auf den Vortagesschluss (KST).
    }

    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? {
        jsonObject.optJSONObject("error")?.let { return it.getString("message") }
        return jsonObject.getString("message")
    }

    override val bulkTickersNumOfRequests: Int get() = 1

    override fun getBulkTickersUrl(requestId: Int): String? = allTickersUrl

    /** Nur die beobachteten Paare; kennt die Börse eines nicht, folgt die ungefilterte Abfrage. */
    override fun getBulkTickersUrl(requestId: Int, pairIds: Collection<String>): String? =
        if (pairIds.isEmpty()) allTickersUrl
        else "$base/ticker?markets=${pairIds.sorted().joinToString(",")}"

    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONObject { item ->
            val market = item.optString("market").ifEmpty { return@forEachJSONObject }
            val ticker = SimpleTicker()
            runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject }
            tickers[market] = ticker
        }
    }
}
