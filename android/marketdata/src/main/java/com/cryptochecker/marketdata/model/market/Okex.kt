package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

/** Alle Spot-Ticker — zugleich Paarliste und Sammelabfrage. */
private const val OKX_SPOT_TICKERS = "https://www.okx.com/api/v5/market/tickers?instType=SPOT"

/** OKX Spot. Kennung «BASE-QUOTE». */
class Okex : SimpleMarket(
    name = "OKX",
    pairListUrl = OKX_SPOT_TICKERS,
    tickerUrl = "https://www.okx.com/api/v5/market/ticker?instId=%1\$s",
    errorField = "msg",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}-${info.quote}"

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONArray("data").getJSONObject(0), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = OKX_SPOT_TICKERS

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("instId"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { row ->
            val instId = row.getString("instId")
            val legs = instId.split('-')
            if (legs.size == 2) out += MarketPair(legs[0], legs[1], instId)
        }
    }

    // ── Lesen ──

    /** Je Paar dieselbe Struktur in Einzel- und Sammelabfrage. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.high = row.getDouble("high24h")
        ticker.low = row.getDouble("low24h")
        ticker.vol = row.getDouble("vol24h")
        ticker.volQuote = row.getDouble("volCcy24h")
        // Leeres Buch: OKX schickt "" für bidPx/askPx — dann ohne Geld-/Briefkurs statt Fehler
        ticker.bid = row.optDoubleOrMissing("bidPx")
        ticker.ask = row.optDoubleOrMissing("askPx")
        // «ts» = Erzeugung des Tickers (≈ jetzt), kein Handel: Zeit der Börse bleibt offen.
        // «open24h» = Kurs vor 24 h (sodUtc0/sodUtc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open24h"))
    }
}
