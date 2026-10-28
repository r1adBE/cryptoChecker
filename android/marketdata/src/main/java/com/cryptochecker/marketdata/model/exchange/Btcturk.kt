package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val BTCTURK_API = "https://api.btcturk.com/api/v2"

/** BtcTurk (Türkei), API v2: https://docs.btcturk.com — Kennung wie «BTCTRY». */
class Btcturk : SimpleExchange(
    name = "BtcTurk",
    pairListUrl = "$BTCTURK_API/server/exchangeinfo",
    tickerUrl = "$BTCTURK_API/ticker?pairSymbol=%1\$s",
    ttsName = "B T C Turk",
    errorField = "message",
) {
    override val key = "Btcturk"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONArray("data").getJSONObject(0), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BTCTURK_API/ticker"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("pair"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONObject("data").getJSONArray("symbols").forEachJSONObject { symbol ->
            if (symbol.optString("status") != "TRADING") return@forEachJSONObject
            out += ExchangePair(symbol.getString("numerator"), symbol.getString("denominator"), symbol.getString("name"))
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile. `timestamp` gehört zur Momentaufnahme, nicht zum letzten Handel — Zeit der Börse
     * bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
        // «open» = Kurs vor 24 h; «dailyPercent» (Prozent) nur ersatzweise
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
            ?: Change24h.percent(row.optDouble("dailyPercent"))
    }
}
