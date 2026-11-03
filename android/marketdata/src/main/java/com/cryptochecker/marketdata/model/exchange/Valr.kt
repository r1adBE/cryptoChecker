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

private const val VALR_API = "https://api.valr.com/v1/public"

/**
 * VALR (Südafrika, FSCA-Lizenz), öffentliche API: https://docs.valr.com/ — Kennung wie «BTCZAR». Fehler als
 * `{"code", "message"}`.
 */
class Valr : SimpleExchange(
    name = "VALR",
    pairListUrl = "$VALR_API/pairs",
    tickerUrl = "$VALR_API/%1\$s/marketsummary",
    errorField = "message",
) {
    override val key = "Valr"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote).uppercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Nicht vollständig: Die Gesamtliste lässt aktive Spot-Paare aus (Aufzeichnung 03.11.2026: 33 von 204, z. B.
     * DOGEUSDC) — ein fehlendes Paar fragt die App einzeln.
     */
    override fun getBulkTickersUrl(call: Int): String = "$VALR_API/marketsummary"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("currencyPair"), row, "lastTradedPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur aktive Spot-Paare (`currencyPairType` «SPOT»); Futures nicht. */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { pair ->
            if (!pair.optBoolean("active", false) || pair.optString("currencyPairType") != "SPOT") {
                return@forEachJSONObject
            }
            out += ExchangePair(
                pair.getText("baseCurrency").uppercase(),
                pair.getText("quoteCurrency").uppercase(),
                pair.getText("symbol"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Zeile (Zahlen als Text): `previousClosePrice` = Kurs vor 24 h (gleitend, wandert mit der Zeit;
     * `changeFromPrevious` passt dazu), Hoch/Tief und Volumen über 24 h. `created` ist die Zeit der Momentaufnahme.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("lastTradedPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("previousClosePrice"))
    }
}
