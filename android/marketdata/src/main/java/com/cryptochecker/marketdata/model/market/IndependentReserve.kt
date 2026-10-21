package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.forEachString
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val INDEPENDENT_RESERVE_API = "https://api.independentreserve.com/Public"

/**
 * Independent Reserve (Australien, Neuseeland, Singapur). Statt einer Paarliste gibt es zwei Listen — Coins
 * (Xbt, Eth, …) und Gegenwährungen (Aud, Usd, Nzd, Sgd); jedes Paar daraus ist handelbar ([pairListCombined],
 * darum kein `SimpleMarket`). Bitcoin heisst dort «Xbt». https://www.independentreserve.com/features/api
 */
class IndependentReserve : Market("Independent Reserve", "Independent Reserve") {
    override val key = "IndependentReserve"

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val stored = info.pairId?.split('_')
        val (coin, counter) = if (stored != null && stored.size == 2) {
            stored[0] to stored[1]
        } else {
            apiCode(info.base) to apiCode(info.quote)
        }
        return "$INDEPENDENT_RESERVE_API/GetMarketSummary?primaryCurrencyCode=$coin&secondaryCurrencyCode=$counter"
    }

    /** `CreatedTimestampUtc` ist die Erstellung der Übersicht, kein Handel: Zeit der Börse unbekannt. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        ticker.last = json.getDouble("LastPrice")
        ticker.bid = json.optDoubleOrMissing("CurrentHighestBidPrice")
        ticker.ask = json.optDoubleOrMissing("CurrentLowestOfferPrice")
        ticker.high = json.optDoubleOrMissing("DayHighestPrice")
        ticker.low = json.optDoubleOrMissing("DayLowestPrice")
        // Heisst immer «…Xbt», gilt aber für den jeweiligen Coin. «DayVolumeXbt» zählt alle Gegenwährungen zusammen,
        // «…InSecondaryCurrrency» (so geschrieben, drei r) nur dieses Paar — beides in Coins, nicht in der Gegenwährung
        ticker.vol = json.optDoubleOrMissing("DayVolumeXbtInSecondaryCurrrency")
    }

    /** `getString` wie bisher: JSON null ergibt hier den Text «null». */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? = json.getString("Message")

    // ── Paarliste: Coins (0) mal Gegenwährungen (1) ──

    override val pairListCallCount: Int
        get() = 2

    override val pairListCombined: Boolean
        get() = true

    override fun pairListAddress(call: Int): String =
        INDEPENDENT_RESERVE_API + if (call == 0) "/GetValidPrimaryCurrencyCodes" else "/GetValidSecondaryCurrencyCodes"

    override fun fillPairListCombined(bodies: List<String>, out: MutableList<MarketPair>) {
        val coins = codeList(bodies[0])
        val counters = codeList(bodies[1])
        for (coin in coins) {
            for (counter in counters) {
                if (coin.equals(counter, ignoreCase = true)) continue
                out += MarketPair(displayCode(coin), displayCode(counter), coin + "_" + counter)
            }
        }
    }

    // ── Lesen ──

    /** Nicht leere Codes einer Liste. */
    private fun codeList(body: String): List<String> =
        buildList { JSONArray(body).forEachString { code -> if (code.isNotBlank()) add(code) } }

    /** Code der Börse → Anzeige («Xbt» → «BTC», sonst gross). */
    private fun displayCode(code: String): String = code.uppercase().let { if (it == "XBT") "BTC" else it }

    /** Anzeige → Code der Börse («BTC» → «Xbt», sonst «Eth»-Schreibweise). */
    private fun apiCode(currency: String): String {
        val upper = currency.uppercase()
        if (upper == "BTC") return "Xbt"
        return upper.lowercase().replaceFirstChar { it.titlecase() }
    }
}
