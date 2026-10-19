package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val BITBANK_API = "https://public.bitbank.cc"

/** Alle Paare in einer Antwort; dient auch als Paarliste (dieselbe Adresse, kein zweiter Host). */
private const val BITBANK_TICKERS = "$BITBANK_API/tickers"

/** Fehlercode «zu viele Anfragen» — bitbank schickt nur Codes, keinen Text. */
private const val BITBANK_TOO_MANY_REQUESTS = 10009

/**
 * bitbank (Japan, JPY-Paare), öffentliche API: https://github.com/bitbankinc/bitbank-api-docs. Kennung «btc_jpy».
 * Jede Antwort steckt in {"success":1,"data":…}; Fehler sind {"success":0,"data":{"code":…}} ohne Text.
 */
class Bitbank : SimpleMarket(
    name = "bitbank",
    pairListUrl = BITBANK_TICKERS,
    tickerUrl = "$BITBANK_API/%1\$s/ticker",
    ttsName = "bit bank",
) {
    override val key = "Bitbank"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.baseLower + "_" + info.quoteLower)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data"), ticker)
    }

    /**
     * Nur ein Code. Der Text für «zu viele Anfragen» enthält «Too many requests», damit der Bericht ihn als
     * Bremsung erkennt (RateLimitSignal); andere Codes erscheinen als «Error code …».
     */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optInt("success", 1) != 0) noErrorText()
        val code = json.optJSONObject("data")?.optInt("code", 0) ?: 0
        if (code <= 0) noErrorText()
        return if (code == BITBANK_TOO_MANY_REQUESTS) "Too many requests ($code)" else "Error code $code"
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** `/tickers` ist als Liste aller Paare dokumentiert. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = BITBANK_TICKERS

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optText("pair"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Aus `/tickers`: jedes Paar mit Kennung «basis_gegenwährung». */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { row ->
            val pair = row.optText("pair")
            val sides = pair.split('_')
            if (sides.size == 2) out += MarketPair(sides[0].uppercase(), sides[1].uppercase(), pair)
        }
    }

    // ── Lesen ──

    /**
     * Ein Ticker. `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
     * `sell`/`buy` = bestes Angebot/Gebot.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        // «open» laut Doku: Eröffnung vor 24 Stunden (gleitend)
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }
}
