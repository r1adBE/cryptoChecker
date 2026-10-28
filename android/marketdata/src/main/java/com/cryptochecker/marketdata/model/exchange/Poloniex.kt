package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val POLONIEX_MARKETS = "https://api.poloniex.com/markets"

/** Poloniex Spot (API v3, api-docs.poloniex.com/spot). */
class Poloniex : SimpleExchange(
    name = "Poloniex",
    pairListUrl = POLONIEX_MARKETS,
    tickerUrl = "$POLONIEX_MARKETS/%1\$s/ticker24h",
    errorField = "message",
) {
    override val key = "Poloniex"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}_${info.quote}"

    /** Normal ein Objekt; kommt doch eine Liste, zählt ihr erster Eintrag. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val text = body.trimStart()
        val row = if (text.startsWith("[")) JSONArray(text).getJSONObject(0) else JSONObject(text)
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$POLONIEX_MARKETS/ticker24h"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "close", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        JSONArray(body).forEachJSONObject { market ->
            if (market.optString("state") != "NORMAL") return@forEachJSONObject
            out += ExchangePair(
                market.getString("baseCurrencyName"),
                market.getString("quoteCurrencyName"),
                market.getString("symbol"),
            )
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val close = row.getDouble("close")
        ticker.last = close
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("quantity")
        ticker.volQuote = row.optDoubleOrMissing("amount")
        // «ts» = Momentaufnahme, kein Handel. «open» = Kurs vor 24 h; «dailyChange» (Bruchteil) nur als Ersatz
        ticker.change24hPercent = Change24h.fromOpen(close, row.optDouble("open"))
            ?: Change24h.fraction(row.optDouble("dailyChange"))
    }
}
