package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

/** Gemeinsame API-Wurzel von Spot und Futures. */
private const val BITGET_API = "https://api.bitget.com/api/v2"

/** Produktart der USDT-Perpetuals, Teil jeder Futures-Adresse. */
private const val BITGET_USDT_FUTURES = "productType=USDT-FUTURES"

/** Bitget Spot (API v2). */
class Bitget : BitgetBase(
    name = "Bitget",
    pairListUrl = "$BITGET_API/spot/public/symbols",
    tickerUrl = "$BITGET_API/spot/market/tickers?symbol=%1\$s",
    allTickersUrl = "$BITGET_API/spot/market/tickers",
) {
    override val key = "Bitget"

    override fun pairOf(entry: JSONObject): MarketPair? {
        if (entry.optString("status") != "online") return null
        return MarketPair(entry.getString("baseCoin"), entry.getString("quoteCoin"), entry.getString("symbol"))
    }
}

/** Bitget USDT-Perpetuals. */
class BitgetFutures : BitgetBase(
    name = "Bitget Futures",
    pairListUrl = "$BITGET_API/mix/market/contracts?$BITGET_USDT_FUTURES",
    tickerUrl = "$BITGET_API/mix/market/ticker?symbol=%1\$s&$BITGET_USDT_FUTURES",
    allTickersUrl = "$BITGET_API/mix/market/tickers?$BITGET_USDT_FUTURES",
) {
    override val key = "BitgetFutures"

    override fun pairOf(entry: JSONObject): MarketPair? {
        val perpetual = entry.optString("symbolType") == "perpetual"
        val normal = entry.optString("symbolStatus") == "normal"
        if (!perpetual || !normal) return null
        return MarketPair(
            entry.getString("baseCoin"),
            entry.getString("quoteCoin"),
            entry.getString("symbol"),
            FuturesContractType.PERPETUAL,
            TradFi.bitget(entry.optString("isRwa")),
        )
    }
}

/**
 * Spot und Futures unterscheiden sich nur in Adressen und Paarfilter ([pairOf]); Antworten haben die
 * Form {"code", "msg", "data": [...]}, die Ticker-Felder heissen gleich.
 */
abstract class BitgetBase(
    name: String,
    pairListUrl: String,
    tickerUrl: String,
    private val allTickersUrl: String,
) : SimpleMarket(name = name, pairListUrl = pairListUrl, tickerUrl = tickerUrl, errorField = "msg") {

    /** Paar aus einem Eintrag der Paarliste; null = nicht wählbar. */
    protected abstract fun pairOf(entry: JSONObject): MarketPair?

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONArray("data").getJSONObject(0), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = allTickersUrl

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "lastPr", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { entry -> pairOf(entry)?.let { out += it } }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPr")
        ticker.bid = row.optDoubleOrMissing("bidPr")
        ticker.ask = row.optDoubleOrMissing("askPr")
        ticker.high = row.optDoubleOrMissing("high24h")
        ticker.low = row.optDoubleOrMissing("low24h")
        ticker.vol = row.optDoubleOrMissing("baseVolume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // `ts` = Zeitpunkt der Momentaufnahme, kein Handel → Zeit der Börse unbekannt.
        // «change24h» ist gleitend (Bruchteil); «changeUtc24h» (ab 0 Uhr UTC) passt nicht.
        ticker.change24hPercent = Change24h.fraction(row.optDouble("change24h"))
    }
}
