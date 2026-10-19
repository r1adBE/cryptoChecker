package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val COINDCX_API = "https://api.coindcx.com/exchange"

/** Alle Ticker in einer Antwort; einen Einzelabruf je Paar gibt es nicht. */
private const val COINDCX_TICKER = "$COINDCX_API/ticker"

/**
 * CoinDCX (Indien, INR- und USDT-Paare), öffentliche API: https://docs.coindcx.com — Kennung = `coindcx_name`
 * («BTCINR»), im Ticker `market`. Vorsicht: In der Paarliste ist `base_currency_short_name` die Gegenwährung
 * (INR/USDT) und `target_currency_short_name` der gehandelte Coin.
 */
class CoinDcx : SimpleMarket(
    name = "CoinDCX",
    pairListUrl = "$COINDCX_API/v1/markets_details",
    tickerUrl = COINDCX_TICKER,
    ttsName = "Coin D C X",
    errorField = "message",
) {
    override val key = "CoinDcx"

    // ── Einzelkurs: aus der Gesamtliste das eigene Paar ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote)

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val id = pairId(info)
        var found: JSONObject? = null
        JSONArray(body).forEachJSONObject { row -> if (found == null && row.optText("market") == id) found = row }
        copyRow(found ?: throw MarketParseException(MarketParseException.NO_DATA), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = COINDCX_TICKER

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("market"), row, "last_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur aktive Märkte; dieselbe Kennung zählt einmal (die Liste führt Märkte mehrerer Orderbücher). */
    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        val seen = HashSet<String>()
        JSONArray(body).forEachJSONObject { entry ->
            if (entry.optString("status") != "active") return@forEachJSONObject
            val id = entry.getText("coindcx_name")
            if (!seen.add(id)) return@forEachJSONObject
            out += MarketPair(
                entry.getText("target_currency_short_name").uppercase(),
                entry.getText("base_currency_short_name").uppercase(),
                id,
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile. `volume` zählt nach den Beispielen der Doku in der Gegenwährung (darum [Ticker.volQuote]);
     * `change_24_hour` ist die Veränderung über 24 h in Prozent. `timestamp` (s) gehört zur Momentaufnahme, nicht
     * zum letzten Handel — Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_price")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.volQuote = row.optDoubleOrMissing("volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("change_24_hour"))
    }
}
