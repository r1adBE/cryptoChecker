package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val BITKUB_API = "https://api.bitkub.com/api/v3/market"

/** Fehlercode 11 der API v3 = «Invalid symbol» (Doku, Abschnitt «Error Codes»). */
private const val BITKUB_INVALID_SYMBOL = 11

/**
 * Bitkub (Thailand), öffentliche API v3: https://github.com/bitkub/bitkub-official-api-docs (rest-v3.md) — Kennung
 * wie «BTC_THB». Ticker als Liste (mit `sym` ein Eintrag, ohne alle); Fehler als `{"error": <Code>}`.
 */
class Bitkub : SimpleExchange(
    name = "Bitkub",
    pairListUrl = "$BITKUB_API/symbols",
    tickerUrl = "$BITKUB_API/ticker?sym=%1\$s",
) {
    override val key = "Bitkub"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "_" + info.quote.uppercase())

    /** Liste mit dem einen Paar; leer = Paar unbekannt. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val rows = JSONArray(body)
        if (rows.length() == 0) throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(rows.getJSONObject(0), ticker)
    }

    /** `{"error": 11}` = unbekanntes Paar («Invalid symbol», erkennt NotTraded); 0 = kein Fehler. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val code = json.optInt("error", 0)
        if (code == 0) noErrorText()
        return if (code == BITKUB_INVALID_SYMBOL) "Invalid symbol" else "Bitkub error $code"
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Der Ticker ohne `sym` führt jedes Paar mit Status «active» (Aufzeichnung 03.11.2026: 362 von 362), ein
     * fehlendes Paar ist eingestellt.
     */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BITKUB_API/ticker"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Status «active»; «stopped» hat keinen Kurs mehr. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("result").forEachJSONObject { market ->
            if (market.optString("status") != "active") return@forEachJSONObject
            out += ExchangePair(
                market.getText("base_asset").uppercase(),
                market.getText("quote_asset").uppercase(),
                market.getText("symbol"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile (Zahlen als Text): Gebot/Angebot, Hoch/Tief über 24 h, Volumen in Basis- und Gegenwährung.
     * Nicht übernommen: `percent_change` (Zeitraum nicht dokumentiert, die App rechnet mit Kerzen); eine Zeit des
     * letzten Handels liefert Bitkub nicht.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        ticker.high = row.optDoubleOrMissing("high_24_hr")
        ticker.low = row.optDoubleOrMissing("low_24_hr")
        ticker.vol = row.optDoubleOrMissing("base_volume")
        ticker.volQuote = row.optDoubleOrMissing("quote_volume")
    }
}
