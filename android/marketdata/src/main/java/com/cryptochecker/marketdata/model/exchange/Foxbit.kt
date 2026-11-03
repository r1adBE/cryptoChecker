package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val FOXBIT_API = "https://api.foxbit.com.br/rest/v3"

/**
 * Foxbit (Brasilien), öffentliche API v3: https://docs.foxbit.com.br/rest/v3/ — Kennung wie «btcbrl» (klein).
 * Ticker unter `data` mit verschachtelten Teilen: `last_trade`, `rolling_24h` (gleitende 24 h), `best` (Buch).
 */
class Foxbit : SimpleExchange(
    name = "Foxbit",
    pairListUrl = "$FOXBIT_API/markets",
    tickerUrl = "$FOXBIT_API/markets/%1\$s/ticker/24hr",
) {
    override val key = "Foxbit"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote).lowercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.getJSONArray("data").optJSONObject(0)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    /** `{"error": {"message", "details": [...]}}`: die erste Einzelheit, sonst die Meldung. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val error = json.optJSONObject("error") ?: noErrorText()
        val detail = error.optJSONArray("details")?.optString(0).orEmpty()
        return detail.ifBlank { error.getText("message") }
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Alle Märkte in einer Antwort (Aufzeichnung 03.11.2026: 131 von 131 der Paarliste). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$FOXBIT_API/markets/ticker/24hr"

    /** Ohne letzten Handel (oder mit Kurs 0) fällt der Markt still weg. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            val id = row.optText("market_symbol")
            val trade = row.optJSONObject("last_trade")
            if (id.isEmpty() || trade == null || BulkTickerRows.inactivePrice(trade, "price")) return@forEachJSONObject
            bulkRow(id, tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("data").forEachJSONObject { market ->
            out += ExchangePair(
                market.getJSONObject("base").getText("symbol").uppercase(),
                market.getJSONObject("quote").getText("symbol").uppercase(),
                market.getText("symbol"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Kurs des letzten Handels, Gebot/Angebot aus `best`, Hoch/Tief/Volumen und `open` (Kurs vor 24 h) aus
     * `rolling_24h`. Die Handelszeit kommt nur als ISO-Text und bleibt ungenutzt (Abrufzeit).
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getJSONObject("last_trade").getDouble("price")
        val day = row.optJSONObject("rolling_24h") ?: JSONObject()
        val best = row.optJSONObject("best") ?: JSONObject()
        ticker.last = last
        ticker.bid = best.optJSONObject("bid")?.optDoubleOrMissing("price") ?: Ticker.MISSING
        ticker.ask = best.optJSONObject("ask")?.optDoubleOrMissing("price") ?: Ticker.MISSING
        ticker.high = day.optDoubleOrMissing("high")
        ticker.low = day.optDoubleOrMissing("low")
        ticker.vol = day.optDoubleOrMissing("volume")
        ticker.volQuote = day.optDoubleOrMissing("quote_volume")
        ticker.change24hPercent = Change24h.fromOpen(last, day.optDouble("open"))
    }
}
