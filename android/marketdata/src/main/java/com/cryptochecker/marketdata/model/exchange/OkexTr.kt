package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val OKX_TR_API = "https://tr.okx.com/api/v5"

/** OKX TR führt die Lira-Märkte; die übrigen Spot-Paare sind dieselben wie bei OKX und bleiben dort. */
private const val OKX_TR_QUOTE = "TRY"

/**
 * OKX TR (Türkei, CMB-Lizenzliste), API v5 wie OKX unter eigener Adresse: https://tr.okx.com/docs-v5/ — Kennung
 * «BTC-TRY». Paarliste aus `public/instruments` (Status «live», nur TRY), Kurse wie bei [Okex].
 */
class OkexTr : SimpleExchange(
    name = "OKX TR",
    pairListUrl = "$OKX_TR_API/public/instruments?instType=SPOT",
    tickerUrl = "$OKX_TR_API/market/ticker?instId=%1\$s",
) {
    override val key = "OkexTr"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "-" + info.quote.uppercase())

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.getJSONArray("data").optJSONObject(0)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    /** `code` ≠ «0» mit `msg`; «0» ist kein Fehler. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optText("code").ifEmpty { "0" } == "0") noErrorText()
        return json.getText("msg")
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Alle Spot-Ticker (auch die TRY-Märkte); jeder Markt mit Status «live» steht darin. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$OKX_TR_API/market/tickers?instType=SPOT"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optText("instId"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Status «live» mit Gegenwährung TRY. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("data").forEachJSONObject { inst ->
            if (inst.optString("state") != "live" || inst.optString("quoteCcy") != OKX_TR_QUOTE) {
                return@forEachJSONObject
            }
            out += ExchangePair(inst.getText("baseCcy"), inst.getText("quoteCcy"), inst.getText("instId"))
        }
    }

    // ── Lesen ──

    /** Wie OKX: `open24h` = Kurs vor 24 h; `ts` ist die Erzeugung des Tickers, kein Handel. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPx")
        ticker.ask = row.optDoubleOrMissing("askPx")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("vol24h")
        ticker.volQuote = row.optDoubleOrMissing("volCcy24h")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open24h"))
    }
}
