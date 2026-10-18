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

private const val BITVAVO_API = "https://api.bitvavo.com/v2"

/** 24-h-Ticker: mit `market` ein Paar, ohne Parameter alle. */
private const val BITVAVO_DAY_TICKER = "$BITVAVO_API/ticker/24h"

/** Bitvavo (EU, EUR-Paare), API v2. Kennung = `market` («BTC-EUR»). */
class Bitvavo : SimpleMarket(
    name = "Bitvavo",
    pairListUrl = "$BITVAVO_API/markets",
    tickerUrl = "$BITVAVO_DAY_TICKER?market=%1\$s",
    errorField = "error",
) {
    override val key = "Bitvavo"

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = BITVAVO_DAY_TICKER

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("market"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { market ->
            if (market.optString("status") != "trading") return@forEachJSONObject
            out += MarketPair(market.getString("base"), market.getString("quote"), market.getString("market"))
        }
    }

    // ── Lesen ──

    /**
     * Ein 24-h-Eintrag. `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt
     * unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("volumeQuote")
        // «open» bei /ticker/24h: Kurs am Anfang des gleitenden Fensters
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }
}
