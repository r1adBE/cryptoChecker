package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import org.json.JSONObject

private const val QUIDAX_API = "https://app.quidax.io/api/v1/markets"

/**
 * Quidax (Nigeria, SEC-Lizenz), öffentliche API: https://docs.quidax.io/ — Kennung wie «btcngn» (klein). Antworten
 * `{"status", "message", "data"}`; ein Ticker unter `data.ticker`, die Gesamtliste als Objekt je Markt.
 */
class Quidax : SimpleExchange(
    name = "Quidax",
    pairListUrl = QUIDAX_API,
    tickerUrl = "$QUIDAX_API/tickers/%1\$s",
) {
    override val key = "Quidax"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote).lowercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data").getJSONObject("ticker"), ticker)
    }

    /** `status` «error» mit `message`; «success» ist kein Fehler. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optString("status") != "error") noErrorText()
        return json.getText("message")
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Jeder Markt der Paarliste steht in der Gesamtliste (Aufzeichnung 03.11.2026: 79 von 79). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$QUIDAX_API/tickers"

    /** Kurs 0 (nie gehandelt) fällt still weg. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body).getJSONObject("data")
        for (id in all.keys()) {
            val row = all.optJSONObject(id)?.optJSONObject("ticker") ?: continue
            bulkRowIfActive(id, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("data").forEachJSONObject { market ->
            out += ExchangePair(
                market.getText("base_unit").uppercase(),
                market.getText("quote_unit").uppercase(),
                market.getText("id"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile (Zahlen als Text): `buy`/`sell` = bestes Gebot/Angebot, `open` = Kurs vor 24 h, `vol` in der
     * Basiswährung. Hoch/Tief «0.0» heisst: kein Handel in 24 h (keine Angabe). `at` ist die Zeit der Antwort.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = positiveOrMissing(row.optDouble("buy"))
        ticker.ask = positiveOrMissing(row.optDouble("sell"))
        ticker.high = positiveOrMissing(row.optDouble("high"))
        ticker.low = positiveOrMissing(row.optDouble("low"))
        ticker.vol = row.optDouble("vol").takeIf { it.isFinite() && it >= 0.0 } ?: Ticker.MISSING
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }

    private fun positiveOrMissing(value: Double): Double = if (value.isFinite() && value > 0.0) value else Ticker.MISSING
}
