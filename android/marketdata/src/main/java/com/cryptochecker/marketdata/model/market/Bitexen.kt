package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

/** Alle Märkte in einer Antwort; sie dient zugleich als Paarliste. */
private const val BITEXEN_TICKER = "https://www.bitexen.com/api/v1/ticker/"

/** Interner Grund, wenn `status` nicht «success» ist (angezeigt wird der Fehlertext der Börse, falls vorhanden). */
private const val BITEXEN_NOT_SUCCESS = "Status not success"

/**
 * Bitexen (Türkei, TRY-Paare), öffentliche API v1. Kennung = `market_code` («BTCTRY»). Antwort
 * {"status": "success", "data": {"ticker": {"BTCTRY": {…}}}}; Einzelkurs und Paarliste lesen dieselbe Gesamtliste.
 */
class Bitexen : SimpleMarket(
    name = "Bitexen",
    pairListUrl = BITEXEN_TICKER,
    tickerUrl = BITEXEN_TICKER,
    errorField = "message",
) {
    override val key = "Bitexen"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = rows(json).optJSONObject(pairId(info)) ?: throw MarketParseException(MarketParseException.NO_DATA)
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = BITEXEN_TICKER

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = rows(JSONObject(body))
        for (code in all.keys()) {
            // Kein Objekt: keine Ticker-Zeile, still übergehen
            val row = all.optJSONObject(code) ?: continue
            bulkRowIfActive(code, row, "last_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Coin und Gegenwährung aus dem Unterobjekt `market`; Zeilen ohne beides fallen weg. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        val all = rows(json)
        for (code in all.keys()) {
            val market = all.optJSONObject(code)?.optJSONObject("market") ?: continue
            out += MarketPair(
                market.optString("base_currency_code").uppercase(),
                market.optString("counter_currency_code").uppercase(),
                code,
            )
        }
    }

    // ── Lesen ──

    /**
     * Die Zeilen unter data.ticker. Jeder andere Status als «success» ist ein Fehler — nicht «keine Daten», sonst
     * gälte jedes Paar als nicht mehr gehandelt.
     */
    private fun rows(json: JSONObject): JSONObject {
        if (json.optString("status") != "success") throw MarketParseException(BITEXEN_NOT_SUCCESS)
        return json.getJSONObject("data").getJSONObject("ticker")
    }

    /**
     * Eine Ticker-Zeile (Zahlen als Text), `volume_24h` in der Basiswährung. Nicht übernommen: `change_24h` (Einheit
     * und Zeitraum nicht verlässlich belegt, die App rechnet dann mit Kerzen) und `timestamp` (Momentaufnahme, kein
     * Handel).
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_price")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high_24h")
        ticker.low = row.optDoubleOrMissing("low_24h")
        ticker.vol = row.optDoubleOrMissing("volume_24h")
    }
}
