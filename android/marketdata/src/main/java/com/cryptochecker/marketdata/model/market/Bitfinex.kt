package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONArray
import org.json.JSONArray

/**
 * Bitfinex Spot. Antworten sind Arrays statt Objekte:
 * Ticker = [BID, BID_SIZE, ASK, ASK_SIZE, DAILY_CHANGE, DAILY_CHANGE_REL, LAST, VOLUME, HIGH, LOW]
 * Bitfinex nennt USDT intern „UST“; angezeigt wird USDT.
 */
class Bitfinex : Market("Bitfinex", "Bitfinex", null) {

    override fun getUrl(requestId: Int, checkerInfo: CheckerInfo): String =
        "https://api-pub.bitfinex.com/v2/ticker/${checkerInfo.currencyPairId}"

    override fun parseTicker(requestId: Int, responseString: String, ticker: Ticker, checkerInfo: CheckerInfo) {
        val array = JSONArray(responseString)
        // Fehler kommen als ["error", code, "meldung"]
        if (array.optString(0) == "error") throw MarketParseException(array.optString(2))
        read(array, 0, ticker)
    }

    override fun parseError(requestId: Int, responseString: String, checkerInfo: CheckerInfo): String? =
        JSONArray(responseString).optString(2).ifEmpty { null }

    private fun read(array: JSONArray, offset: Int, ticker: Ticker) {
        ticker.bid = array.getDouble(offset + 0)
        ticker.ask = array.getDouble(offset + 2)
        ticker.last = array.getDouble(offset + 6)
        ticker.vol = array.getDouble(offset + 7)
        ticker.high = array.getDouble(offset + 8)
        ticker.low = array.getDouble(offset + 9)
        // DAILY_CHANGE_RELATIVE: gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(array.optDouble(offset + 5))
    }

    // ---- Massenabfrage
    override val bulkTickersNumOfRequests: Int get() = 1
    override fun getBulkTickersUrl(requestId: Int): String = "https://api-pub.bitfinex.com/v2/tickers?symbols=ALL"

    override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
        JSONArray(responseString).forEachJSONArray { item ->
            val symbol = item.optString(0)
            if (!symbol.startsWith("t")) return@forEachJSONArray   // „f…“ = Funding, keine Handelspaare
            val ticker = SimpleTicker()
            runCatching { read(item, 1, ticker) }.onFailure { return@forEachJSONArray }
            tickers[symbol] = ticker
        }
    }
    override val bulkTickersComplete: Boolean get() = true

    // ---- Handelspaare
    override fun getCurrencyPairsUrl(requestId: Int): String =
        "https://api-pub.bitfinex.com/v2/conf/pub:list:pair:exchange"

    override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
        val list = JSONArray(responseString).getJSONArray(0)
        for (i in 0 until list.length()) {
            val pair = list.getString(i)
            if (pair.startsWith("TEST")) continue
            val (base, quote) = when {
                ':' in pair -> pair.substringBefore(':') to pair.substringAfter(':')
                pair.length == 6 -> pair.substring(0, 3) to pair.substring(3)
                else -> continue
            }
            pairs.add(CurrencyPairInfo(displayName(base), displayName(quote), "t$pair"))
        }
    }

    private fun displayName(asset: String) = if (asset == "UST") "USDT" else asset
}
