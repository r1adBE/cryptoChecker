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

private const val MAX_API = "https://max-api.maicoin.com/api/v3"

/** Sammelabfrage nur mit Auswahl: je Markt ein «markets[]=» (ohne Auswahl antwortet v3 mit einem Fehler). */
private const val MAX_TICKERS = "$MAX_API/tickers?"

/** Kennungen, die unverändert in die Adresse dürfen (MAX: Kleinbuchstaben und Ziffern, «btctwd»). */
private val MAX_SAFE_ID = Regex("[a-z0-9]+")

/**
 * MAX (MaiCoin, Taiwan), öffentliche API v3: https://max.maicoin.com/documents/api — Kennung wie «btctwd» (klein).
 * Fehler als `{"success": false, "error": {"code", "message"}}`.
 */
class MaxMaicoin : SimpleExchange(
    name = "MAX",
    pairListUrl = "$MAX_API/markets",
    tickerUrl = "$MAX_API/ticker?market=%1\$s",
) {
    override val key = "MaxMaicoin"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote).lowercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.optJSONObject("error")?.getText("message") ?: noErrorText()

    // ── Sammelabfrage: nur gefiltert ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Alle beobachteten Märkte in einer Anfrage. Ein unbekannter Markt lässt die ganze Anfrage scheitern; dann lädt
     * die App die Paarliste, lässt Unbekannte weg und fragt neu ([getBulkKnownPairsUrl], wie BTC Markets). Kennungen
     * mit anderen Zeichen als [MAX_SAFE_ID] bleiben draussen.
     */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        if (call != 0) return null
        val ids = pairIds.filter(MAX_SAFE_ID::matches).distinct().sorted()
        if (ids.isEmpty()) return null
        return MAX_TICKERS + ids.joinToString("&") { "markets%5B%5D=$it" }
    }

    override fun getBulkKnownPairsUrl(call: Int): String? = pairListAddress(call)

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("market"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Status «active». */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { market ->
            if (market.optString("status") != "active") return@forEachJSONObject
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
     * Basis-, `vol_in_quote` in der Gegenwährung. `at` ist die Zeit der Momentaufnahme, kein Handel — die Zeit der
     * Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        ticker.volQuote = row.optDoubleOrMissing("vol_in_quote")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }
}
