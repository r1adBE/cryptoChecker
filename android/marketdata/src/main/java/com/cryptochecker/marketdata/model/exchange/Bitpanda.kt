package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import org.json.JSONObject

/** Alle Kurse in einer Antwort (je Coin ein Objekt mit einem Preis je Fiat-Währung); sie dient auch als Paarliste. */
private const val BITPANDA_TICKER = "https://api.bitpanda.com/v1/ticker"

/**
 * Edelmetalle führt Bitpanda je Gramm (XAU/EUR rund 113 statt rund 3500 je Unze): nicht angeboten, sonst stünde unter
 * dem üblichen Kürzel ein anderer Massstab.
 */
private val BITPANDA_METALS = setOf("XAU", "XAG", "XPT", "XPD")

/**
 * Bitpanda (Broker, Österreich; DE/AT/CH/IT/PL u. a.), öffentlicher Ticker ohne Schlüssel:
 * https://developers.bitpanda.com/ — nur der Momentkurs je Coin und Fiat-Währung, ohne Gebot/Angebot, Hoch/Tief,
 * Volumen und 24-h-Veränderung (die App rechnet sie mit Kerzen wie bei bitFlyer), ohne Zeit der Börse. Kennung
 * «BTC_EUR». Einzelkurs, Sammelabfrage und Paarliste lesen dieselbe Gesamtliste.
 */
class Bitpanda : SimpleExchange(
    name = "Bitpanda",
    pairListUrl = BITPANDA_TICKER,
    tickerUrl = BITPANDA_TICKER,
) {
    override val key = "Bitpanda"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "_" + info.quote.uppercase())

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val (coin, fiat) = split(pairId(info)) ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val row = json.optJSONObject(coin)?.takeIf { coin !in BITPANDA_METALS && it.has(fiat) }
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, fiat, ticker)
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

    override fun getBulkTickersUrl(call: Int): String = BITPANDA_TICKER

    /** Preis 0 (Coin ohne Kurs in dieser Währung) fällt still weg; Edelmetalle nicht (je Gramm). */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body)
        for (coin in all.keys()) {
            val row = all.optJSONObject(coin) ?: continue
            if (coin.isEmpty() || coin in BITPANDA_METALS) continue
            for (fiat in row.keys()) {
                bulkRowIfActive(coin + "_" + fiat, row, fiat, tickers, failed) { copyRow(row, fiat, it) }
            }
        }
    }

    // ── Paarliste ──

    /** Je Coin und Fiat-Währung mit Kurs über 0 ein Paar; Edelmetalle nicht (je Gramm, siehe oben). */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        for (coin in json.keys()) {
            val row = json.optJSONObject(coin) ?: continue
            if (coin.isEmpty() || coin in BITPANDA_METALS) continue
            for (fiat in row.keys()) {
                val price = row.optDouble(fiat)
                if (fiat.isEmpty() || !price.isFinite() || price <= 0.0) continue
                out += ExchangePair(coin.uppercase(), fiat.uppercase(), coin + "_" + fiat)
            }
        }
    }

    // ── Lesen ──

    /** Nur der Kurs (Text); alle übrigen Felder bleiben «keine Angabe». */
    private fun copyRow(row: JSONObject, fiat: String, ticker: Ticker) {
        ticker.last = row.getDouble(fiat)
    }

    /** «BTC_EUR» → (BTC, EUR); andere Form → null. */
    private fun split(pairId: String): Pair<String, String>? {
        val parts = pairId.split('_')
        return if (parts.size == 2 && parts.all(String::isNotEmpty)) parts[0] to parts[1] else null
    }
}
