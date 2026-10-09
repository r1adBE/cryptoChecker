package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONObject
import java.net.URLEncoder

/** Ein DEX-Pool aus der Suche. */
data class DexPool(
    val chainId: String,
    val dexId: String,
    val pairAddress: String,
    val baseSymbol: String,
    val quoteSymbol: String,
    val priceUsd: Double?,
    val liquidityUsd: Double?,
) {
    /** Kennung für die Kursabfrage: „chain/pairAddress“. */
    val pairId: String get() = "$chainId/$pairAddress"

    /**
     * Quote in der Watchlist: Kurse kommen in USD; die Chain hängt dran,
     * damit gleichnamige Token auf verschiedenen Chains unterscheidbar sind.
     */
    val watchQuote: String get() = "USD ($chainId)"
}

/**
 * Kurse dezentraler Börsen (Uniswap, PancakeSwap, Raydium …) über DexScreener.
 * Kein Paar-Sync: Pools werden über die Suche gefunden und einzeln abgefragt.
 * Frei, ohne Schlüssel; Limit laut Anbieter rund 300 Abfragen pro Minute.
 */
class DexScreener : Market("DexScreener", "Dex Screener", null) {

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String =
        "$BASE/latest/dex/pairs/${checkerInfo.currencyPairId}"

    override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) {
        val pair = jsonObject.optJSONArray("pairs")?.optJSONObject(0)
            ?: jsonObject.optJSONObject("pair")
            ?: throw MarketParseException("Pool not found")

        ticker.last = pair.getString("priceUsd").toDouble()
        pair.optJSONObject("volume")?.let { ticker.volQuote = it.optDouble("h24", Ticker.NO_DATA.toDouble()) }
        // priceChange.h24 = gleitende 24 h in Prozent
        ticker.change24hPercent = pair.optJSONObject("priceChange")?.let { Change24h.percent(it.optDouble("h24")) }
    }

    companion object {
        private const val BASE = "https://api.dexscreener.com"

        fun searchUrl(query: String): String =
            "$BASE/latest/dex/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")

        /** Suchergebnis, nach Liquidität sortiert (meist der relevante Pool zuerst). */
        fun parseSearch(responseString: String): List<DexPool> {
            val result = mutableListOf<DexPool>()
            JSONObject(responseString).optJSONArray("pairs")?.forEachJSONObject { p ->
                val base = p.optJSONObject("baseToken") ?: return@forEachJSONObject
                val quote = p.optJSONObject("quoteToken") ?: return@forEachJSONObject
                result += DexPool(
                    chainId = p.optString("chainId"),
                    dexId = p.optString("dexId"),
                    pairAddress = p.optString("pairAddress"),
                    baseSymbol = base.optString("symbol"),
                    quoteSymbol = quote.optString("symbol"),
                    priceUsd = p.optString("priceUsd").toDoubleOrNull(),
                    liquidityUsd = p.optJSONObject("liquidity")?.optDouble("usd")?.takeUnless { it.isNaN() },
                )
            }
            return result
                .filter { it.chainId.isNotEmpty() && it.pairAddress.isNotEmpty() && it.baseSymbol.isNotEmpty() && it.priceUsd != null }
                .sortedByDescending { it.liquidityUsd ?: 0.0 }
        }
    }
}
