package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val SWYFTX_INFO = "https://api.swyftx.com.au/markets/info/basic/"

/** Swyftx nennt Kauf- und Verkaufspreis in AUD. */
private const val SWYFTX_QUOTE = "AUD"

/**
 * Swyftx (Australien/Neuseeland, Broker), öffentliche API: https://docs.swyftx.com.au/ — nur Kauf- (`buy`) und
 * Verkaufspreis (`sell`) in AUD je Coin, kein letzter Handel: der Kurs ist der Mittelkurs ([priceIsMid]). Ohne
 * 24-h-Veränderung (Kerzen), ohne Zeit; `volume24H` ist das weltweite Volumen, nicht das von Swyftx (nicht
 * übernommen). Kennung = Kürzel («BTC»); Paarliste und Sammelabfrage lesen dieselbe Gesamtliste.
 */
class Swyftx : SimpleExchange(
    name = "Swyftx",
    pairListUrl = SWYFTX_INFO,
    tickerUrl = "$SWYFTX_INFO%1\$s/",
) {
    override val key = "Swyftx"

    override val priceIsMid: Boolean
        get() = true

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: info.base.uppercase()

    /** Liste mit dem einen Coin; leer oder ohne Preise = kein Kurs. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val row = JSONArray(body).optJSONObject(0)?.takeIf(::tradable)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    /** `{"error": {"error", "message"}}`. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.optJSONObject("error")?.getText("message") ?: noErrorText()

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = SWYFTX_INFO

    /** Coins ohne Preise (nicht handelbar) fallen still weg. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            val code = row.optText("code")
            if (code.isEmpty() || !tradable(row)) return@forEachJSONObject
            bulkRow(code, tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /**
     * Je handelbarem Coin ein Paar gegen AUD. Ein Kürzel steht teils zweimal (alter und neuer Token, gleiche Preise,
     * z. B. «DATA»): nur das erste zählt.
     */
    override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
        val seen = HashSet<String>()
        JSONArray(body).forEachJSONObject { row ->
            if (!tradable(row)) return@forEachJSONObject
            val code = row.getText("code")
            if (!seen.add(code)) return@forEachJSONObject
            out += ExchangePair(code.uppercase(), SWYFTX_QUOTE, code)
        }
    }

    // ── Lesen ──

    /** Verkaufspreis = Gebot, Kaufpreis = Angebot, Kurs = Mitte; fehlt ein Preis, ist die Zeile unlesbar. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val bid = row.getDouble("sell")
        val ask = row.getDouble("buy")
        ticker.bid = bid
        ticker.ask = ask
        ticker.last = if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else Ticker.MISSING
    }

    /** Beide Preise vorhanden und nicht leer oder 0. */
    private fun tradable(row: JSONObject): Boolean =
        row.has("buy") && row.has("sell") &&
            !BulkTickerRows.inactivePrice(row, "buy") && !BulkTickerRows.inactivePrice(row, "sell")
}
