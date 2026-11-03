package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val WHITEBIT_API = "https://whitebit.com/api"

/**
 * WhiteBIT (Europa/Ukraine), öffentliche API: https://docs.whitebit.com/ — Kennung wie «BTC_USDT». Einzelkurs über
 * v1 (`/public/ticker?market=`, mit Gebot/Angebot und Kurs vor 24 h), Sammelabfrage über v4 (alle Märkte, nur Kurs,
 * Volumen und Veränderung in Prozent).
 */
class WhiteBit : SimpleExchange(
    name = "WhiteBIT",
    pairListUrl = "$WHITEBIT_API/v4/public/markets",
    tickerUrl = "$WHITEBIT_API/v1/public/ticker?market=%1\$s",
) {
    override val key = "WhiteBit"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "_" + info.quote.uppercase())

    /**
     * v1: `result` mit `open` (Kurs vor 24 h), Gebot/Angebot, Hoch/Tief, `volume` (Basis) und `deal` (Gegenwährung).
     * Keine Zeit des letzten Handels.
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.getJSONObject("result")
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("deal")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }

    /**
     * `success` false: `message` als Text oder als Objekt mit einer Liste je Feld («market»: [«Market is not
     * available.»]); dann zählt der erste Text in alphabetischer Feldfolge.
     */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optBoolean("success", true)) noErrorText()
        val fields = json.optJSONObject("message")
        if (fields != null) {
            for (name in fields.keys().asSequence().sorted()) {
                val text = fields.optJSONArray(name)?.optString(0).orEmpty()
                if (text.isNotBlank()) return text
            }
            noErrorText()
        }
        val text = if (json.isNull("message")) "" else json.optString("message")
        return text.ifBlank { noErrorText() }
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** v4 führt jeden Markt der Paarliste (Aufzeichnung 03.11.2026: alle Spot-Märkte), dazu Futures. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$WHITEBIT_API/v4/public/ticker"

    /** Objekt je Markt; eingefrorene Märkte (`isFrozen`) fallen still weg und gelten als nicht gehandelt. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body)
        for (name in all.keys()) {
            val row = all.optJSONObject(name) ?: continue
            if (row.optBoolean("isFrozen", false)) continue
            bulkRowIfActive(name, row, "last_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Liste aller Märkte: nur `type` «spot» mit offenem Handel (`tradesEnabled`); Futures und TradFi-Futures nicht. */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { market ->
            if (market.optString("type") != "spot" || !market.optBoolean("tradesEnabled", false)) {
                return@forEachJSONObject
            }
            out += ExchangePair(
                market.getText("stock").uppercase(),
                market.getText("money").uppercase(),
                market.getText("name"),
            )
        }
    }

    // ── Lesen ──

    /** Eine v4-Zeile: Kurs, Volumen in Basis- und Gegenwährung, `change` = Veränderung über 24 h in Prozent. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_price")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("change"))
    }
}
