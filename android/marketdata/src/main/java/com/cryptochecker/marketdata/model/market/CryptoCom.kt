package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val CRYPTOCOM_API = "https://api.crypto.com/exchange/v1/public"

/** Gegenwährungen, bei denen `vv` (USD) als Volumen in der Gegenwährung gilt. */
private val CRYPTOCOM_DOLLAR_QUOTES = setOf("USD", "USDT", "USDC")

/**
 * Crypto.com Exchange Spot (API v1). Ticker-Felder sind Einzelbuchstaben: a = letzter Kurs, b/k = Geld/Brief,
 * h/l = Hoch/Tief, v = Menge, vv = Umsatz in USD, c = Veränderung, i = Instrument, t = Zeit.
 */
class CryptoCom : SimpleMarket(
    name = "Crypto.com",
    pairListUrl = "$CRYPTOCOM_API/get-instruments",
    tickerUrl = "$CRYPTOCOM_API/get-tickers?instrument_name=%1\$s",
    errorField = "message",
) {

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(dataOf(json).getJSONObject(0), ticker, info.quote)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$CRYPTOCOM_API/get-tickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        dataOf(JSONObject(body)).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("i"), row, "a", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        dataOf(json).forEachJSONObject { entry ->
            val spotPair = entry.optString("inst_type") == "CCY_PAIR"
            if (!spotPair || !entry.optBoolean("tradable")) return@forEachJSONObject
            out += MarketPair(entry.getString("base_ccy"), entry.getString("quote_ccy"), entry.getString("symbol"))
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile. [counter]: Gegenwährung; ohne Angabe aus dem Instrument `i` («BTC_EUR» → EUR).
     * `t` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker, counter: String? = null) {
        ticker.last = row.getDouble("a")
        ticker.bid = row.optDoubleOrMissing("b")
        ticker.ask = row.optDoubleOrMissing("k")
        ticker.high = row.optDoubleOrMissing("h")
        ticker.low = row.optDoubleOrMissing("l")
        ticker.vol = row.optDoubleOrMissing("v")
        // «vv» ist USD-Umsatz: nur bei USD-artiger Gegenwährung das Volumen in der Gegenwährung, sonst keines
        // (die Oberfläche schätzt dann Menge × Kurs, QuickFilters)
        val quote = (counter ?: row.optString("i").substringAfterLast('_', "")).uppercase()
        ticker.volQuote = if (quote in CRYPTOCOM_DOLLAR_QUOTES) row.optDoubleOrMissing("vv") else Ticker.MISSING
        // «c»: gleitende 24 h als Bruchteil (0.0583 = +5,83 %), null ohne Handel
        ticker.change24hPercent = Change24h.fraction(row.optDouble("c"))
    }

    /** `result.data` einer Antwort. */
    private fun dataOf(envelope: JSONObject): JSONArray = envelope.getJSONObject("result").getJSONArray("data")
}
