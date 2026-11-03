package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

/** Alle Märkte in einer Antwort (ein Objekt je «BTC_CAD»); sie dient zugleich als Paarliste. */
private const val NDAX_TICKER = "https://core.ndax.io/v1/ticker"

/**
 * NDAX (Kanada), öffentlicher Ticker im Poloniex-Format: https://apidoc.ndax.io/ — Kennung wie «BTC_CAD». Einzelkurs,
 * Sammelabfrage und Paarliste lesen dieselbe Gesamtliste. `percentChange` hat keinen dokumentierten Zeitraum (die App
 * rechnet mit Kerzen), `quoteVolume` gleicht `baseVolume` (Aufzeichnung 03.11.2026) und bleibt darum weg.
 */
class Ndax : SimpleExchange(
    name = "NDAX",
    pairListUrl = NDAX_TICKER,
    tickerUrl = NDAX_TICKER,
) {
    override val key = "Ndax"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "_" + info.quote.uppercase())

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.optJSONObject(pairId(info))?.takeUnless(::frozen)
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt. */
    override val bulkTickersComplete: Boolean
        get() = true

    /** Der Einzelkurs liest dieselbe Gesamtliste: «kein Eintrag» ist so verlässlich wie in der Sammelabfrage. */
    override val singleTickerReadsFullList: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = NDAX_TICKER

    /** Eingefrorene Märkte (`isFrozen` «1») fallen still weg und gelten als nicht gehandelt. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body)
        for (id in all.keys()) {
            val row = all.optJSONObject(id) ?: continue
            if (split(id) == null || frozen(row)) continue
            bulkRowIfActive(id, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Eingefrorene Märkte und solche ohne Kurs (`last` 0, nie gehandelt, z. B. «USD_CAD») fallen weg. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        for (id in json.keys()) {
            val row = json.optJSONObject(id) ?: continue
            if (frozen(row) || BulkTickerRows.inactivePrice(row, "last")) continue
            val (base, quote) = split(id) ?: continue
            out += ExchangePair(base, quote, id)
        }
    }

    // ── Lesen ──

    /** Kurs, Gebot/Angebot, Hoch/Tief über 24 h und Volumen in der Basiswährung (Zahlen als Text); keine Zeit. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("highestBid")
        ticker.ask = row.optDoubleOrMissing("lowestAsk")
        ticker.high = row.optDoubleOrMissing("high24hr")
        ticker.low = row.optDoubleOrMissing("low24hr")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
    }

    private fun frozen(row: JSONObject): Boolean = row.optString("isFrozen") == "1"

    /** «BTC_CAD» → (BTC, CAD); andere Form → null. */
    private fun split(id: String): Pair<String, String>? {
        val parts = id.uppercase().split('_')
        return if (parts.size == 2 && parts.all(String::isNotEmpty)) parts[0] to parts[1] else null
    }
}
