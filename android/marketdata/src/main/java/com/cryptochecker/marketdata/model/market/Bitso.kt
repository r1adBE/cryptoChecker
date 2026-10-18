package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val BITSO_API = "https://bitso.com/api/v3"

/** Bitso (Lateinamerika), API v3: https://docs.bitso.com — Bücher heissen «btc_mxn». */
class Bitso : SimpleMarket(
    name = "Bitso",
    pairListUrl = "$BITSO_API/available_books",
    tickerUrl = "$BITSO_API/ticker?book=%1\$s",
) {
    override val key = "Bitso"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: (info.baseLower + "_" + info.quoteLower)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("payload"), ticker)
    }

    /** Fehlerform {"error": {"message": …}}. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.getJSONObject("error").getText("message")

    // ── Sammelabfrage: derselbe Endpunkt ohne «book» liefert alle Bücher ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = "$BITSO_API/ticker"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("payload").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("book"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("payload").forEachJSONObject { entry ->
            val book = entry.optString("book")
            val sides = book.split('_')
            if (sides.size == 2) out += MarketPair(sides[0].uppercase(), sides[1].uppercase(), book)
        }
    }

    // ── Lesen ──

    /**
     * Ein Buch-Eintrag. `created_at` ist die Zeit der Momentaufnahme, kein Handel — die Zeit der Börse
     * bleibt unbekannt, sonst wirkte ein ruhiger Markt frisch.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        // «change_24»: absolute Veränderung über 24 h
        ticker.change24hPercent = Change24h.fromAbsolute(last, row.optDouble("change_24"))
    }
}
