package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

/** Alle Märkte in einer Antwort; sie dient zugleich als Paarliste. */
private const val PARIBU_TICKER = "https://www.paribu.com/ticker"

/** Paribu schreibt die Lira «TL»; angezeigt und verglichen wird der ISO-Code. */
private const val PARIBU_LIRA = "TL"
private const val ISO_LIRA = "TRY"

/**
 * Paribu (Türkei), öffentlicher Ticker: ein Objekt je Markt unter Schlüsseln wie «BTC_TL» (= Kennung).
 * Einzelkurs und Paarliste lesen dieselbe Gesamtliste.
 */
class Paribu : SimpleExchange(
    name = "Paribu",
    pairListUrl = PARIBU_TICKER,
    tickerUrl = PARIBU_TICKER,
) {
    override val key = "Paribu"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + "_" + paribuCode(info.quote))

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.optJSONObject(pairId(info)) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Paarliste und Kurse stammen aus derselben Antwort: Was fehlt, wird nicht gehandelt. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = PARIBU_TICKER

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body)
        for (name in all.keys()) {
            // Kein Objekt oder kein «COIN_GEGENWÄHRUNG»: keine Ticker-Zeile, still übergehen
            val row = all.optJSONObject(name) ?: continue
            if (split(name) == null) continue
            bulkRowIfActive(name, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        for (name in json.keys()) {
            if (json.optJSONObject(name) == null) continue
            val (coin, counter) = split(name) ?: continue
            out += ExchangePair(coin, if (counter == PARIBU_LIRA) ISO_LIRA else counter, name)
        }
    }

    // ── Lesen ──

    /**
     * Eine Zeile: `highestBid`/`lowestAsk` = bestes Gebot/Angebot, Hoch/Tief über 24 h. Nicht übernommen: `volume`
     * (Einheit nicht belegt), `percentChange` (Zeitraum nicht belegt, die App rechnet dann mit Kerzen); eine Zeit
     * des letzten Handels liefert Paribu nicht.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("highestBid")
        ticker.ask = row.optDoubleOrMissing("lowestAsk")
        ticker.high = row.optDoubleOrMissing("high24hr")
        ticker.low = row.optDoubleOrMissing("low24hr")
    }

    /** «BTC_TL» → (BTC, TL); andere Form → null. */
    private fun split(name: String): Pair<String, String>? {
        val parts = name.uppercase().split('_')
        return if (parts.size == 2 && parts.all(String::isNotEmpty)) parts[0] to parts[1] else null
    }

    private fun paribuCode(quote: String): String =
        if (quote.uppercase() == ISO_LIRA) PARIBU_LIRA else quote.uppercase()
}
