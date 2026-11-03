package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val BIT2ME_API = "https://gateway.bit2me.com"

/**
 * Bit2Me (Spanien), öffentliche API: https://api.bit2me.com/ — Kennung wie «BTC/EUR». Ticker als Liste (mit
 * `symbol` ein Eintrag, ohne alle Märkte); Fehler als `{"statusCode", "message"}`.
 */
class Bit2Me : SimpleExchange(
    name = "Bit2Me",
    pairListUrl = "$BIT2ME_API/v1/trading/market-config",
    tickerUrl = "$BIT2ME_API/v2/trading/tickers?symbol=%1\$s",
    errorField = "message",
) {
    override val key = "Bit2Me"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "/" + info.quote.uppercase())

    /** Liste mit dem einen Markt; leer = unbekannt. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val row = JSONArray(body).optJSONObject(0) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Ohne `symbol` jeder Markt (Aufzeichnung 03.11.2026: alle 274 freigegebenen der Paarliste). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BIT2ME_API/v2/trading/tickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "close", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur `marketEnabled` «enabled» («frozen» = gesperrt); «BTC/EUR» → BTC, EUR. */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { market ->
            if (market.optString("marketEnabled") != "enabled") return@forEachJSONObject
            val id = market.optText("symbol")
            val parts = id.split('/')
            if (parts.size != 2 || parts.any(String::isEmpty)) return@forEachJSONObject
            out += ExchangePair(parts[0].uppercase(), parts[1].uppercase(), id)
        }
    }

    // ── Lesen ──

    /**
     * Eine Zeile (Zahlen als Zahl): `close` = letzter Kurs, `open` = Kurs vor 24 h (gleitend, er wandert mit der Zeit),
     * Volumen in Basis- und Gegenwährung. `timestamp` ist nicht als Handelszeit dokumentiert — bleibt ungenutzt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("close")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }
}
