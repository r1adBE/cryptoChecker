package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.PostRequestInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.optDoubleNoData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hyperliquid — dezentrale Perpetual-Börse, abgerechnet in USDC.
 * Eine einzige POST-Abfrage liefert Liste und Kurse aller Perps:
 * [ { universe: [ {name, …}, … ] }, [ {markPx, midPx, dayNtlVlm, dayBaseVlm, …}, … ] ]
 * Beide Listen sind über den Index verbunden.
 */
class Hyperliquid : Market("Hyperliquid", "Hyperliquid", null) {

    private fun request() = PostRequestInfo(
        body = """{"type":"metaAndAssetCtxs"}""",
        headers = mapOf("Content-Type" to "application/json")
    )

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String = INFO_URL
    override fun getPostRequestInfo(requestId: Int, checkerInfo: CheckerInfo): PostRequestInfo = request()

    override fun parseTicker(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo) {
        val all = parseAll(responseString)
        val found = all[checkerInfo.currencyPairId] ?: throw MarketParseException("Unknown coin: ${checkerInfo.currencyPairId}")
        ticker.last = found.last
        ticker.vol = found.vol
        ticker.volQuote = found.volQuote
    }

    override fun parseError(requestId: Int, responseString: String, checkerInfo: CheckerInfo): String? =
        responseString.take(200)

    // ---- Massenabfrage: dieselbe Abfrage
    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = INFO_URL
    override fun getBulkTickersPostRequestInfo(requestId: Int): PostRequestInfo = request()
    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        tickers.putAll(parseAll(responseString))
    }
    override val bulkTickersComplete: Boolean get() = true

    // ---- Paare
    override fun getCurrencyPairsUrl(requestId: Int): String = INFO_URL
    override fun getCurrencyPairsPostRequestInfo(requestId: Int): PostRequestInfo = request()

    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        val universe = JSONArray(responseString).getJSONObject(0).getJSONArray("universe")
        for (i in 0 until universe.length()) {
            val coin = universe.getJSONObject(i)
            if (coin.optBoolean("isDelisted")) continue
            val name = coin.getString("name")
            pairs.add(CurrencyPairInfo(name, "USDC", name, FuturesContractType.PERPETUAL))
        }
    }

    private fun parseAll(responseString: String): Map<String, SimpleTicker> {
        val root = JSONArray(responseString)
        val universe = root.getJSONObject(0).getJSONArray("universe")
        val contexts = root.getJSONArray(1)
        val result = HashMap<String, SimpleTicker>()
        for (i in 0 until minOf(universe.length(), contexts.length())) {
            val coin = universe.getJSONObject(i)
            if (coin.optBoolean("isDelisted")) continue
            val ctx: JSONObject = contexts.optJSONObject(i) ?: continue
            // midPx fehlt bei dünnem Orderbuch, dann der Mark-Preis.
            val price = ctx.optDouble("midPx", Double.NaN).takeUnless { it.isNaN() } ?: ctx.optDouble("markPx", Double.NaN)
            if (price.isNaN()) continue
            result[coin.getString("name")] = SimpleTicker().apply {
                last = price
                vol = ctx.optDoubleNoData("dayBaseVlm")
                volQuote = ctx.optDoubleNoData("dayNtlVlm")
            }
        }
        return result
    }

    private companion object {
        const val INFO_URL = "https://api.hyperliquid.xyz/info"
    }
}
