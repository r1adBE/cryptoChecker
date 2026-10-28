package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val ONE_TRADING_API = "https://api.onetrading.com/fast/v1"

/** Alle Kurse auf einmal: Sammelabfrage und Ausweg des Einzelkurses. */
private const val ONE_TRADING_TICKERS = "$ONE_TRADING_API/market-ticker"

/** Instrumenttyp und Buchzustand, wie in «instruments» und «market-ticker». */
private const val SPOT = "SPOT"
private const val ACTIVE = "ACTIVE"

/** One Trading (EU, vormals Bitpanda Pro), nur Spot. Doku: docs.onetrading.com */
class OneTrading : SimpleExchange(
    name = "One Trading",
    pairListUrl = "$ONE_TRADING_API/instruments",
    tickerUrl = "$ONE_TRADING_API/market-ticker/%1\$s",
    errorField = "error",
) {
    override val key = "OneTrading"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}_${info.quote}"

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    /** Einzelkurs mit Serverfehler (HTTP 5xx, etwa seit Oktober 2026): Kurs aus der Sammelliste. */
    override fun tickerFallbackAddress(call: Int, info: PairQuery): String = ONE_TRADING_TICKERS

    /**
     * Die Zeile des Paars aus der Sammelliste, Zeilen anderer Art (Futures) zählen nicht — wie [parseBulkTickers].
     * Fehlt sie: [ExchangeParseException.NO_DATA]; die App behält dann den ursprünglichen Serverfehler.
     */
    override fun fillTickerFallback(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val id = pairId(info)
        val rows = JSONArray(body)
        val row = (0 until rows.length()).map(rows::getJSONObject)
            .firstOrNull { !isOtherType(it) && it.optString("instrument_code") == id }
        copyRow(row ?: throw ExchangeParseException(ExchangeParseException.NO_DATA), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = ONE_TRADING_TICKERS

    /**
     * Die Sammelliste enthält auch Futures («type» DATED_FUTURE, EQUITY_FUTURE …): nur Spot-Zeilen zählen. Zeilen
     * ohne «type» werden gelesen (Art unbekannt), machen die Antwort aber unvollständig ([bulkResponseComplete]).
     */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            if (isOtherType(row)) return@forEachJSONObject
            bulkRowIfActive(row.optString("instrument_code"), row, "last_price", tickers, failed) { copyRow(row, it) }
        }
    }

    /**
     * Vollständig nur nach den Spot-Zeilen: Jede Zeile hat eine Art, und jedes Spot-Buch ist ACTIVE. Zeilen anderer Art
     * (Futures) fallen weg, ohne dass ein Spot-Paar fehlen könnte. Fehlt die Art einer Zeile oder ist ein Spot-Buch nicht
     * ACTIVE (etwa CLOSED), kann ein fehlendes Paar auch nur vorübergehend fehlen: dann entscheidet die Einzelabfrage
     * statt «nicht mehr gehandelt».
     */
    override fun bulkResponseComplete(call: Int, body: String): Boolean {
        var complete = true
        JSONArray(body).forEachJSONObject { row ->
            val type = typeOf(row)
            if (type.isEmpty() || (type == SPOT && row.optString("state") != ACTIVE)) complete = false
        }
        return complete
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { inst ->
            val activeSpot = inst.optString("type") == SPOT && inst.optString("state") == ACTIVE
            if (!activeSpot) return@forEachJSONObject
            out += ExchangePair(codeOf(inst, "base"), codeOf(inst, "quote"), inst.getString("id"))
        }
    }

    // ── Lesen ──

    /** Zeile eines anderen Instrumenttyps (Futures); ohne «type» nicht (Art unbekannt). */
    private fun isOtherType(row: JSONObject): Boolean {
        val type = typeOf(row)
        return type.isNotEmpty() && type != SPOT
    }

    /**
     * «type» der Zeile; fehlend, JSON-null und der Text «null» gelten gleich als keine Art (leer). optString gäbe für
     * JSON-null den Text «null» zurück — so wie unter iOS.
     */
    private fun typeOf(row: JSONObject): String {
        if (row.isNull("type")) return ""
        val type = row.optString("type")
        return if (type == "null") "" else type
    }

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
