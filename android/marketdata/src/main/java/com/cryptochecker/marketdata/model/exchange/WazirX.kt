package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val WAZIRX_API = "https://api.wazirx.com/sapi/v1"

/**
 * WazirX (Indien), öffentliche API: https://docs.wazirx.com/ — Kennung wie «btcinr» (klein). Fehler als
 * `{"code", "message"}`.
 */
class WazirX : SimpleExchange(
    name = "WazirX",
    pairListUrl = "$WAZIRX_API/exchangeInfo",
    tickerUrl = "$WAZIRX_API/ticker/24hr?symbol=%1\$s",
    errorField = "message",
) {
    override val key = "WazirX"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote).lowercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Nicht vollständig: Die Gesamtliste liess ein handelbares Paar aus (Aufzeichnung 03.11.2026: «husdt») — ein
     * fehlendes Paar fragt die App einzeln.
     */
    override fun getBulkTickersUrl(call: Int): String = "$WAZIRX_API/tickers/24hr"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Status «trading». */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("symbols").forEachJSONObject { entry ->
            if (entry.optString("status") != "trading") return@forEachJSONObject
            out += ExchangePair(
                entry.getText("baseAsset").uppercase(),
                entry.getText("quoteAsset").uppercase(),
                entry.getText("symbol"),
            )
        }
    }

    // ── Lesen ──

    /**
     * 24-h-Ticker (Zahlen als Text): `openPrice` = Kurs vor 24 h, `volume` in der Basiswährung. `at` ist die Zeit der
     * Momentaufnahme, kein Handel — die Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("openPrice"))
    }
}
