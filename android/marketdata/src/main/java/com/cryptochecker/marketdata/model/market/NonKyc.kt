package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val NONKYC_API = "https://api.nonkyc.io/api/v2"

/** NonKYC.io (API v2). Marktliste mit «BASE/QUOTE», Ticker-Kennung «BASE_QUOTE». */
class NonKyc : SimpleMarket(
    name = "NonKYC",
    pairListUrl = "$NONKYC_API/market/getlist",
    tickerUrl = "$NONKYC_API/ticker/%1\$s",
    ttsName = "Non K Y C",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: underscored(info.base, info.quote)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    /** Fehler: {"error":{"message":…}}, {"error":"…"} oder {"message":…}. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val nested = json.optJSONObject("error")
        if (nested != null) return nested.getText("message")
        return json.optText("error").ifEmpty { json.getText("message") }
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = "$NONKYC_API/tickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(rowId(row), row, "last_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { market ->
            val active = market.optBoolean("isActive") || market.optBoolean("active")
            val sides = market.optString("symbol").split('/')
            if (!active || sides.size != 2) return@forEachJSONObject
            val (left, right) = sides
            // «primaryTicker» ist das eigentliche Kürzel der Basis, sonst der linke Teil
            out += MarketPair(market.optString("primaryTicker").ifEmpty { left }, right, underscored(left, right))
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_price")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("target_volume")
    }

    /** «ticker_id», sonst aus Basis und Ziel zusammengesetzt; ohne beides leer (Zeile fällt weg). */
    private fun rowId(row: JSONObject): String {
        val tickerId = row.optString("ticker_id")
        if (tickerId.isNotEmpty()) return tickerId
        val base = row.optString("base_currency")
        val target = row.optString("target_currency")
        return if (base.isEmpty() || target.isEmpty()) "" else underscored(base, target)
    }

    private fun underscored(base: String, quote: String): String = "${base}_$quote"
}
