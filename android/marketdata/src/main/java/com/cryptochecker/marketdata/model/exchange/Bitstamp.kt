package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val BITSTAMP_API = "https://www.bitstamp.net/api/v2"

/** Bitstamp (EU-reguliert, auch EUR-/GBP-Paare), API v2. Kennung = `url_symbol` («btcusd»). */
class Bitstamp : SimpleExchange(
    name = "Bitstamp",
    pairListUrl = "$BITSTAMP_API/trading-pairs-info/",
    tickerUrl = "$BITSTAMP_API/ticker/%1\$s/",
    errorField = "reason",
) {
    override val key = "Bitstamp"

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage: alle Ticker; «pair» = «BTC/USD» → Kennung «btcusd» ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = "$BITSTAMP_API/ticker/"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            val pairId = row.optString("pair").replace("/", "").lowercase()
            bulkRowIfActive(pairId, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { entry ->
            if (entry.optString("trading") != "Enabled") return@forEachJSONObject
            // Anzeigename wie «BTC/USD»
            val label = entry.getString("name")
            val slash = label.indexOf('/')
            if (slash < 0) return@forEachJSONObject
            out += ExchangePair(label.substring(0, slash), label.substring(slash + 1), entry.getString("url_symbol"))
        }
    }

    // ── Lesen ──

    /**
     * Ein Ticker-Eintrag. `timestamp` gehört zur Momentaufnahme, nicht zum letzten Handel — Zeit der Börse
     * bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        // «open_24» = Kurs vor 24 h («open» wäre der Tagesbeginn); sonst die Prozentangabe
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open_24"))
            ?: Change24h.percent(row.optDouble("percent_change_24"))
    }
}
