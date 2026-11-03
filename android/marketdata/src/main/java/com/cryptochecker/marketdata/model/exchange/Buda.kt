package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val BUDA_API = "https://www.buda.com/api/v2"

/**
 * Buda (Chile, Kolumbien, Peru), öffentliche API v2: https://api.buda.com/ — Kennung wie «BTC-CLP». Beträge kommen
 * als Paar [Wert, Währung]; `price_variation_24h` ist ein Anteil (−0.037 = −3,7 %).
 */
class Buda : SimpleExchange(
    name = "Buda",
    pairListUrl = "$BUDA_API/markets",
    tickerUrl = "$BUDA_API/markets/%1\$s/ticker",
    errorField = "message",
) {
    override val key = "Buda"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "-" + info.quote.uppercase())

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.getJSONObject("ticker")
        copyRow(row, ticker)
        ticker.bid = amount(row, "max_bid")
        ticker.ask = amount(row, "min_ask")
        ticker.vol = amount(row, "volume")
        ticker.volQuote = amount(row, "quote_volume")
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Jeder Markt in einer Antwort (Aufzeichnung 03.11.2026: 26 von 26), nur Kurs und Veränderung. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BUDA_API/tickers"

    /** Kurs 0 fällt still weg. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("tickers").forEachJSONObject { row ->
            val id = row.optText("market_id")
            val price = row.optJSONArray("last_price")
            if (id.isEmpty() || (price != null && BulkTickerRows.inactivePrice(price, 0))) return@forEachJSONObject
            bulkRow(id, tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Gesperrte Märkte (`disabled`) fallen weg. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("markets").forEachJSONObject { market ->
            if (market.optBoolean("disabled", false)) return@forEachJSONObject
            out += ExchangePair(
                market.getText("base_currency").uppercase(),
                market.getText("quote_currency").uppercase(),
                market.getText("id"),
            )
        }
    }

    // ── Lesen ──

    /** Kurs und Veränderung über 24 h (Anteil × 100); Einzel- und Sammelabfrage. Keine Zeit des letzten Handels. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getJSONArray("last_price").getDouble(0)
        ticker.change24hPercent = Change24h.fraction(row.optDouble("price_variation_24h"))
    }

    /** Wert eines Betrags [Wert, Währung]; fehlt er, «keine Angabe». */
    private fun amount(row: JSONObject, field: String): Double =
        row.optJSONArray(field)?.optDouble(0)?.takeIf { it.isFinite() } ?: Ticker.MISSING
}
