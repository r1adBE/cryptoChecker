package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val ZEBPAY_API = "https://sapi.zebpay.com/api/v2"

/** Statuswerte (klein geschrieben), bei denen ein Paar nicht handelbar ist. */
private val ZEBPAY_STOPPED = setOf("closed", "close", "halt", "halted", "suspended", "delisted", "inactive", "disabled")

/** ZebPay (Indien), Spot-API v2. Referenz: github.com/zebpay/zebpay-api-references */
class ZebPay : SimpleMarket(
    name = "ZebPay",
    pairListUrl = "$ZEBPAY_API/ex/exchangeInfo",
    tickerUrl = "$ZEBPAY_API/market/ticker?symbol=%1\$s",
    ttsName = "Zeb Pay",
    errorField = "message",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}-${info.quote}"

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data"), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = "$ZEBPAY_API/market/allTickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONObject("data").getJSONArray("symbols").forEachJSONObject { symbol ->
            if (!isTradable(symbol)) return@forEachJSONObject
            out += MarketPair(symbol.getString("baseAsset"), symbol.getString("quoteAsset"), symbol.getString("symbol"))
        }
    }

    // ── Lesen ──

    /** «timestamp» = Momentaufnahme (≈ jetzt), kein Handel: Zeit der Börse bleibt offen. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
    }

    /** Gesperrter Status oder ausdrücklich «enableTrading»: false → nicht handelbar. */
    private fun isTradable(symbol: JSONObject): Boolean {
        if (symbol.optString("status").lowercase() in ZEBPAY_STOPPED) return false
        return !symbol.has("enableTrading") || symbol.optBoolean("enableTrading", true)
    }
}
