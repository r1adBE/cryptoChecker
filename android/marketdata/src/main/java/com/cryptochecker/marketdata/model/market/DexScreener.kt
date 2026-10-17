package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject
import java.net.URLEncoder

private const val DEXSCREENER_API = "https://api.dexscreener.com"

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
    /** Kennung der Kursabfrage: «chain/pairAddress». */
    val pairId: String get() = "$chainId/$pairAddress"

    /**
     * Gegenwährung in der Merkliste: Kurse sind USD, die Chain steht dabei, damit gleichnamige Token
     * verschiedener Chains auseinanderzuhalten sind.
     */
    val watchQuote: String get() = "USD ($chainId)"
}

/**
 * Kurse dezentraler Börsen (Uniswap, PancakeSwap, Raydium …) über DexScreener. Keine Paarliste (Pools findet die
 * Suche, [searchUrl]), darum kein `SimpleMarket`; abgefragt wird jeder Pool einzeln. Frei und ohne Schlüssel; laut
 * Anbieter rund 300 Abfragen pro Minute.
 */
class DexScreener : Market("DexScreener", "Dex Screener") {

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String =
        "$DEXSCREENER_API/latest/dex/pairs/${info.pairId}"

    /**
     * Nur der gemerkte Pool zählt: Liefert die API einen anderen zuerst (oder nur einen anderen), gilt das als
     * «nicht gefunden» — nie die Kurse eines fremden Pools.
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val pool = poolsIn(json).firstOrNull { isPool(it.optString("chainId"), it.optString("pairAddress"), info.pairId) }
            ?: throw MarketParseException("Pool not found")

        ticker.last = pool.getString("priceUsd").toDouble()
        pool.optJSONObject("volume")?.let { ticker.volQuote = it.optDoubleOrMissing("h24") }
        // «priceChange.h24»: gleitende 24 h in Prozent
        ticker.change24hPercent = pool.optJSONObject("priceChange")?.let { Change24h.percent(it.optDouble("h24")) }
    }

    // ── Lesen ──

    /** Alle Pools der Antwort: zuerst das Array `pairs` (nur Objekte), dann ein einzelnes `pair`. */
    private fun poolsIn(json: JSONObject): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        json.optJSONArray("pairs")?.forEachOptJSONObject(found::add)
        json.optJSONObject("pair")?.let(found::add)
        return found
    }

    companion object {
        /**
         * Gehört ([chainId], [pairAddress]) zum gemerkten Pool [pairId] («chain/pairAddress»)? Die Adresse
         * vergleicht ohne Gross-/Kleinschreibung (EVM-Prüfsummen). Wie `DexScreener.isPool` (iOS).
         */
        fun isPool(chainId: String, pairAddress: String, pairId: String?): Boolean {
            val wanted = pairId?.trim().orEmpty()
            val cut = wanted.indexOf('/')
            if (cut <= 0 || cut == wanted.lastIndex) return false
            val wantedChain = wanted.substring(0, cut)
            val wantedAddress = wanted.substring(cut + 1)
            if (pairAddress.isEmpty()) return false
            return chainId.equals(wantedChain, ignoreCase = true) && pairAddress.equals(wantedAddress, ignoreCase = true)
        }

        fun searchUrl(query: String): String {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            return "$DEXSCREENER_API/latest/dex/search?q=$encoded"
        }

        /** Treffer der Suche, nach Liquidität absteigend (meist steht der relevante Pool vorn). */
        fun parseSearch(body: String): List<DexPool> {
            val list = JSONObject(body).optJSONArray("pairs") ?: return emptyList()
            val pools = ArrayList<DexPool>()
            list.forEachJSONObject { entry ->
                val base = entry.optJSONObject("baseToken") ?: return@forEachJSONObject
                val quote = entry.optJSONObject("quoteToken") ?: return@forEachJSONObject
                val liquidity = entry.optJSONObject("liquidity")?.optDouble("usd")
                pools += DexPool(
                    chainId = entry.optString("chainId"),
                    dexId = entry.optString("dexId"),
                    pairAddress = entry.optString("pairAddress"),
                    baseSymbol = base.optString("symbol"),
                    quoteSymbol = quote.optString("symbol"),
                    priceUsd = entry.optString("priceUsd").toDoubleOrNull(),
                    liquidityUsd = if (liquidity == null || liquidity.isNaN()) null else liquidity,
                )
            }
            val usable = pools.filter { pool ->
                pool.chainId.isNotEmpty() && pool.pairAddress.isNotEmpty() &&
                    pool.baseSymbol.isNotEmpty() && pool.priceUsd != null
            }
            return usable.sortedByDescending { it.liquidityUsd ?: 0.0 }
        }
    }
}
