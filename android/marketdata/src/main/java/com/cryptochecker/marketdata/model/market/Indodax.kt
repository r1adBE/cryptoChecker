package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val INDODAX_API = "https://indodax.com/api"

/**
 * Indodax (Indonesien). Vorsicht: In der Paarliste ist `base_currency` die Gegenwährung (IDR/USDT) und
 * `traded_currency` der gehandelte Coin. https://github.com/btcid/indodax-official-api-docs
 */
class Indodax : SimpleMarket(
    name = "Indodax",
    pairListUrl = "$INDODAX_API/pairs",
    tickerUrl = "$INDODAX_API/ticker/%1\$s",
    errorField = "error_description",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: (info.baseLower + info.quoteLower)

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("ticker"), ticker, info.baseLower, info.quoteLower)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$INDODAX_API/ticker_all"

    /** Schlüssel «btc_idr» → Paar-Kennung «btcidr». */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val all = JSONObject(body).getJSONObject("tickers")
        for (name in all.keys()) {
            // Kein Objekt: keine Ticker-Zeile, still übergehen
            val row = all.optJSONObject(name) ?: continue
            val sides = name.split('_')
            if (sides.size != 2) continue
            val (coin, counter) = sides
            bulkRowIfActive(coin + counter, row, "last", tickers, failed) { copyRow(row, it, coin, counter) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { entry ->
            if (isSet(entry, "is_maintenance") || isSet(entry, "is_market_suspended")) return@forEachJSONObject
            out += MarketPair(
                entry.getString("traded_currency").uppercase(),
                entry.getString("base_currency").uppercase(),
                entry.getString("id"),
            )
        }
    }

    // ── Lesen ──

    /** Ein Ticker; Volumen heissen «vol_<coin>» bzw. «vol_<gegenwährung>». `server_time` ist kein Handel. */
    private fun copyRow(row: JSONObject, ticker: Ticker, coin: String, counter: String) {
        ticker.last = row.getDouble("last")
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol_$coin")
        ticker.volQuote = row.optDoubleOrMissing("vol_$counter")
    }

    /** Schalter, der als true, 1, "1" oder "true" kommen kann. */
    private fun isSet(entry: JSONObject, field: String): Boolean {
        val value = entry.opt(field)
        return value == true || value == 1 || value == "1" || value == "true"
    }
}
