package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.ExchangeCategory
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val BITUNIX_FAPI = "https://fapi.bitunix.com/api/v1/futures/market"

/** `code` einer normalen Antwort. */
private const val BITUNIX_OK = 0

/**
 * Bitunix Perpetuals, öffentliche Futures-Schnittstelle ohne Schlüssel (10 Abfragen/s je IP).
 * Antworten {"code", "msg", "data": [...]}; Einzel- und Sammelkurs über `tickers` (mit `symbols` gefiltert).
 * Kurs = letzter Handel (`lastPrice`), 24-h-Veränderung gegen `open` (laut Doku die Eröffnung der letzten 24 h),
 * keine Zeit der Börse. Die Paarliste nennt keine Art: Aktien-Kontrakte (AAPL, TSLA) sind nicht als TradFi markiert.
 * Spot fehlt bewusst: die Spot-Schnittstelle hat keinen 24-h-Ticker. iOS: `BitunixFutures.swift`.
 */
class BitunixFutures : SimpleExchange(
    name = "Bitunix Futures",
    pairListUrl = "$BITUNIX_FAPI/trading_pairs",
    tickerUrl = "$BITUNIX_FAPI/tickers?symbols=%1\$s",
) {
    override val key = "BitunixFutures"
    override val category = ExchangeCategory.FUTURES

    // ── Einzelkurs ──

    /** Nur die Zeile des gewünschten Symbols; fehlt sie (oder `data` ist null), «keine Daten». */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val wanted = info.pairId
        var found: JSONObject? = null
        json.optJSONArray("data")?.forEachOptJSONObject { row -> if (row.optText("symbol") == wanted) found = row }
        copyRow(found ?: throw ExchangeParseException(ExchangeParseException.NO_DATA), ticker)
    }

    /** code 0 = Erfolg (dann ist «msg» «Success», kein Fehlertext); sonst «msg» (etwa 20015 «not allowed to trade»). */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optInt("code", -1) == BITUNIX_OK) noErrorText()
        return json.getText("msg")
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Ungefiltert alle Kontrakte (auch einige schon abgemeldete — ein überzähliger Eintrag stört nicht). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BITUNIX_FAPI/tickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachOptJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur `symbolStatus` «OPEN» (nicht «PREVIEW», «CANCEL_ONLY», «STOP»); Basis aus dem Symbol ([baseOf]). */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("data").forEachJSONObject { entry ->
            if (entry.optText("symbolStatus") != "OPEN") return@forEachJSONObject
            val symbol = entry.getString("symbol")
            val quote = entry.getString("quote")
            out += ExchangePair(baseOf(symbol, entry.optText("base"), quote), quote, symbol, FuturesContractType.PERPETUAL)
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getString("lastPrice").toDouble()
        ticker.last = last
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("baseVol")
        ticker.volQuote = row.optDoubleOrMissing("quoteVol")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }

    companion object {
        /**
         * Basis eines Kontrakts: das Symbol ohne die Gegenwährung, wenn es darauf endet («1000PEPEUSDT» → «1000PEPE»;
         * `base` hiesse «PEPE», der Kurs gilt aber für 1000 Stück). Sonst `base`.
         * Wie iOS `BitunixFutures.base(symbol:base:quote:)`.
         */
        fun baseOf(symbol: String, base: String, quote: String): String =
            if (quote.isNotEmpty() && symbol.length > quote.length && symbol.endsWith(quote)) {
                symbol.dropLast(quote.length)
            } else {
                base
            }
    }
}
