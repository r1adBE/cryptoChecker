package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val ONE_TRADING_API = "https://api.onetrading.com/fast/v1"

/** One Trading (EU, vormals Bitpanda Pro), nur Spot. Doku: docs.onetrading.com */
class OneTrading : SimpleMarket(
    name = "One Trading",
    pairListUrl = "$ONE_TRADING_API/instruments",
    tickerUrl = "$ONE_TRADING_API/market-ticker/%1\$s",
    errorField = "error",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}_${info.quote}"

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$ONE_TRADING_API/market-ticker"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("instrument_code"), row, "last_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { inst ->
            val activeSpot = inst.optString("type") == "SPOT" && inst.optString("state") == "ACTIVE"
            if (!activeSpot) return@forEachJSONObject
            out += MarketPair(codeOf(inst, "base"), codeOf(inst, "quote"), inst.getString("id"))
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_price")
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        // Ohne Zeitangabe der Börse; «price_change_percentage» = Prozent über 24 h
        ticker.change24hPercent = Change24h.percent(row.optDouble("price_change_percentage"))
    }

    /** «code» des Unterobjekts [side] («base»/«quote»); fehlt es: leer (Paar fällt dann weg). */
    private fun codeOf(inst: JSONObject, side: String): String =
        inst.optJSONObject(side)?.optString("code").orEmpty()
}
