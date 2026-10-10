package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.TimeUtils
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Independent Reserve (Australien, Neuseeland, Singapur). Es gibt keine
 * Paarliste, nur Coins (Xbt, Eth, …) und Gegenwährungen (Aud, Usd, Nzd, Sgd);
 * die Paare sind alle Kombinationen. Bitcoin heisst dort „Xbt“.
 * https://www.independentreserve.com/features/api
 */
class IndependentReserve : Market("Independent Reserve", "Independent Reserve") {

    override val currencyPairsNumOfRequests: Int get() = 2
    override val currencyPairsCombined: Boolean get() = true

    override fun getCurrencyPairsUrl(requestId: Int): String =
        if (requestId == 0) "$BASE/GetValidPrimaryCurrencyCodes" else "$BASE/GetValidSecondaryCurrencyCodes"

    override fun parseCurrencyPairsCombined(responses: List<String>, pairs: MutableList<CurrencyPairInfo>) {
        val primaries = codes(responses[0])
        val secondaries = codes(responses[1])
        for (primary in primaries) for (secondary in secondaries) {
            if (primary.equals(secondary, ignoreCase = true)) continue
            pairs.add(CurrencyPairInfo(publicName(primary), publicName(secondary), "${primary}_$secondary"))
        }
    }

    private fun codes(response: String): List<String> {
        val array = JSONArray(response)
        return (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
    }

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String {
        val parts = checkerInfo.currencyPairId?.split('_')?.takeIf { it.size == 2 }
            ?: listOf(exchangeName(checkerInfo.currencyBase), exchangeName(checkerInfo.currencyCounter))
        return "$BASE/GetMarketSummary?primaryCurrencyCode=${parts[0]}&secondaryCurrencyCode=${parts[1]}"
    }

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        ticker.last = jsonObject.getDouble("LastPrice")
        ticker.bid = jsonObject.optDoubleNoData("CurrentHighestBidPrice")
        ticker.ask = jsonObject.optDoubleNoData("CurrentLowestOfferPrice")
        ticker.high = jsonObject.optDoubleNoData("DayHighestPrice")
        ticker.low = jsonObject.optDoubleNoData("DayLowestPrice")
        // Heisst immer „…Xbt“, gilt aber für den jeweiligen Coin
        ticker.vol = jsonObject.optDoubleNoData("DayVolumeXbt")
        ticker.timestamp = runCatching {
            // „…Utc“ kommt teils ohne „Z“; ohne Zone scheitert ZonedDateTime – dann als UTC lesen (wie iOS)
            val text = jsonObject.optString("CreatedTimestampUtc")
            TimeUtils.convertISODateToTimestamp(if (text.endsWith("Z") || text.contains('+')) text else text + "Z")
        }.getOrDefault(0L)
    }

    override fun parseErrorFromJsonObject(requestId: Int, jsonObject: JSONObject, checkerInfo: CheckerInfo): String? =
        jsonObject.getString("Message")

    private companion object {
        const val BASE = "https://api.independentreserve.com/Public"

        fun publicName(code: String): String {
            val upper = code.uppercase(Locale.ROOT)
            return if (upper == "XBT") "BTC" else upper
        }

        fun exchangeName(currency: String): String {
            val upper = currency.uppercase(Locale.ROOT)
            return if (upper == "BTC") "Xbt" else upper.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
