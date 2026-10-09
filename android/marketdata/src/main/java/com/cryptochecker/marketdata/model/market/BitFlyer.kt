package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject

/**
 * bitFlyer (Japan, USA, EU) — nur Spot. Der Ticker hat weder 24-h-Hoch/-Tief
 * noch ein Gegenwert-Volumen. https://lightning.bitflyer.com/docs
 */
class BitFlyer : Market("bitFlyer", "bit flyer") {

    override val currencyPairsNumOfRequests: Int get() = MARKET_LISTS.size

    override fun getCurrencyPairsUrl(requestId: Int): String = "$BASE/${MARKET_LISTS[requestId]}"

    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        JSONArray(responseString).forEachJSONObject { item ->
            if (!item.optString("market_type").equals("Spot", ignoreCase = true)) return@forEachJSONObject
            val code = item.optString("product_code")
            val parts = code.split('_')
            if (parts.size != 2) return@forEachJSONObject
            pairs.add(CurrencyPairInfo(parts[0], parts[1], code))
        }
    }

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String =
        "$BASE/ticker?product_code=${checkerInfo.currencyPairId ?: "${checkerInfo.currencyBase}_${checkerInfo.currencyCounter}"}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        ticker.last = jsonObject.getDouble("ltp")
        ticker.bid = jsonObject.optDoubleNoData("best_bid")
        ticker.ask = jsonObject.optDoubleNoData("best_ask")
        ticker.vol = jsonObject.optDoubleNoData("volume_by_product")
    }

    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? =
        jsonObject.getString("error_message")

    private companion object {
        const val BASE = "https://api.bitflyer.com/v1"
        val MARKET_LISTS = listOf("markets", "markets/usa", "markets/eu")
    }
}
