package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val KUCOIN_API = "https://api.kucoin.com/api"

/** KuCoin Spot: Paare aus v2/symbols, Kurse aus market/stats bzw. allTickers. */
class Kucoin : SimpleMarket(
    name = "KuCoin",
    pairListUrl = "$KUCOIN_API/v2/symbols",
    tickerUrl = "$KUCOIN_API/v1/market/stats?symbol=%1\$s",
) {

    // ── Einzelkurs ──

    /** «time» ist die Serverzeit der Statistik, kein Handel — Zeit der Börse bleibt offen. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data"), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = "$KUCOIN_API/v1/market/allTickers"

    /** Auch hier die Hüllenzeit «time» nicht übernehmen. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONObject("data").getJSONArray("ticker").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { symbol ->
            // Nur Paare, die gerade handelbar sind
            if (!symbol.getBoolean("enableTrading")) return@forEachJSONObject
            out += MarketPair(
                symbol.getString("baseCurrency"),
                symbol.getString("quoteCurrency"),
                symbol.getString("symbol"),
            )
        }
    }

    // ── Lesen ──

    /** Einzel- und Sammelabfrage nutzen dieselben Feldnamen. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.optDoubleOrMissing("last")
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        ticker.volQuote = row.optDoubleOrMissing("volValue")
        // changeRate: Bruchteil über gleitende 24 h
        ticker.change24hPercent = Change24h.fraction(row.optDouble("changeRate"))
    }
}
