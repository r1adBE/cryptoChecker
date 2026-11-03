package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

/** Alle öffentlichen Kurse in einer Antwort; sie dient auch als Paarliste. */
private const val COINSPOT_LATEST = "https://www.coinspot.com.au/pubapi/v2/latest"

/** Ohne «_GEGENWÄHRUNG» im Schlüssel gilt der Kurs in AUD. */
private const val COINSPOT_DEFAULT_QUOTE = "AUD"

/** Alte Kürzel der Börse → übliches Kürzel (Stellar hiess «STR», NEO «AntShares»). */
private val COINSPOT_COMMON = mapOf("STR" to "XLM", "ANS" to "NEO")

/**
 * CoinSpot (Australien), öffentliche API v2: https://www.coinspot.com.au/v2/api — nur Gebot, Angebot und letzter
 * Kurs einer kleinen Auswahl von Coins (rund 16, Schlüssel «btc» in AUD, «btc_usdt» in USDT), ohne 24-h-Veränderung,
 * Hoch/Tief, Volumen und Zeit der Börse (die App rechnet die Veränderung mit Kerzen). Einzelkurs, Sammelabfrage und
 * Paarliste lesen dieselbe Gesamtliste; Kennung = Schlüssel der Liste.
 */
class CoinSpot : SimpleExchange(
    name = "CoinSpot",
    pairListUrl = COINSPOT_LATEST,
    tickerUrl = COINSPOT_LATEST,
) {
    override val key = "CoinSpot"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: idFor(info.base, info.quote)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val row = json.optJSONObject("prices")?.optJSONObject(pairId(info))
            ?: throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(row, ticker)
    }

    /** `status` «error» mit `message`; «ok» ist kein Fehler. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optText("status") == "ok") noErrorText()
        return json.getText("message")
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

    override fun getBulkTickersUrl(call: Int): String = COINSPOT_LATEST

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val prices = JSONObject(body).getJSONObject("prices")
        for (id in prices.keys()) {
            val row = prices.optJSONObject(id) ?: continue
            if (split(id) == null) continue
            bulkRowIfActive(id, row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        val prices = json.getJSONObject("prices")
        for (id in prices.keys()) {
            if (prices.optJSONObject(id) == null) continue
            val (coin, quote) = split(id) ?: continue
            out += ExchangePair(common(coin), quote, id)
        }
    }

    // ── Lesen ──

    /** Kurs, Gebot und Angebot (Text); ein Gebot «NaN» (leeres Orderbuch) gilt als nicht geliefert. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.bid = finiteOrMissing(row.optDouble("bid"))
        ticker.ask = finiteOrMissing(row.optDouble("ask"))
    }

    private fun finiteOrMissing(value: Double): Double = if (value.isFinite()) value else Ticker.MISSING

    /** «btc» → (BTC, AUD), «btc_usdt» → (BTC, USDT); andere Form → null. */
    private fun split(id: String): Pair<String, String>? {
        val parts = id.uppercase().split('_')
        if (parts.any(String::isEmpty)) return null
        return when (parts.size) {
            1 -> parts[0] to COINSPOT_DEFAULT_QUOTE
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun common(code: String): String = COINSPOT_COMMON[code] ?: code

    /** Umkehrung für ein Paar ohne gespeicherte Kennung: XLM/AUD → «str», BTC/USDT → «btc_usdt». */
    private fun idFor(base: String, quote: String): String {
        val upper = base.uppercase()
        val coin = (COINSPOT_COMMON.entries.firstOrNull { it.value == upper }?.key ?: upper).lowercase()
        return if (quote.uppercase() == COINSPOT_DEFAULT_QUOTE) coin else coin + "_" + quote.lowercase()
    }
}
