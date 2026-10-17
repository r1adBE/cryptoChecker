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
import com.cryptochecker.marketdata.util.optStrings
import org.json.JSONArray
import org.json.JSONObject

private const val MEXC_SPOT = "https://api.mexc.com/api/v3"
private const val MEXC_CONTRACT = "https://contract.mexc.com/api/v1/contract"

/** Handelbar-Werte von «status» (aktuell «1», ältere Antworten «ENABLED»/«TRADING»). */
private val MEXC_TRADABLE = setOf("1", "ENABLED", "TRADING")

/** MEXC Spot (Aufbau ähnlich Binance v3). */
class Mexc : SimpleMarket(
    name = "MEXC",
    pairListUrl = "$MEXC_SPOT/exchangeInfo",
    tickerUrl = "$MEXC_SPOT/ticker/24hr?symbol=%1\$s",
    errorField = "msg",
) {

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$MEXC_SPOT/ticker/24hr"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("symbols").forEachJSONObject { symbol ->
            val tradable = symbol.optString("status") in MEXC_TRADABLE && symbol.optBoolean("isSpotTradingAllowed", true)
            if (!tradable) return@forEachJSONObject
            out += MarketPair(symbol.getString("baseAsset"), symbol.getString("quoteAsset"), symbol.getString("symbol"))
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // «closeTime» = Fensterende (≈ jetzt), kein Handelszeitpunkt — Zeit der Börse bleibt offen.
        // Veränderung aus «openPrice» (Kurs vor 24 h); sonst «priceChangePercent» (bei MEXC ein Bruchteil)
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("openPrice"))
            ?: Change24h.fraction(row.optDouble("priceChangePercent"))
    }
}

/** MEXC USDT-Perpetuals (contract.mexc.com). */
class MexcFutures : SimpleMarket(
    name = "MEXC Futures",
    pairListUrl = "$MEXC_CONTRACT/detail",
    tickerUrl = "$MEXC_CONTRACT/ticker?symbol=%1\$s",
    errorField = "message",
) {

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data"), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$MEXC_CONTRACT/ticker"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("data").forEachJSONObject { contract ->
            // state 0 = aktiv, futureType 1 = Perpetual
            val active = contract.optInt("state", -1) == 0
            val perpetual = contract.optInt("futureType", 1) == 1
            if (!active || !perpetual) return@forEachJSONObject
            out += MarketPair(
                contract.getString("baseCoin"),
                contract.getString("quoteCoin"),
                contract.getString("symbol"),
                FuturesContractType.PERPETUAL,
                TradFi.mexc(contract.optStrings("conceptPlate"), contract.optInt("type", 1)),
            )
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bid1")
        ticker.ask = row.optDoubleOrMissing("ask1")
        ticker.high = row.optDoubleOrMissing("high24Price")
        ticker.low = row.optDoubleOrMissing("lower24Price")
        // «volume24» zählt Kontrakte: kein Coin-Volumen, nur der Umsatz in der Kotierung
        ticker.vol = Ticker.MISSING
        ticker.volQuote = row.optDoubleOrMissing("amount24")
        // «timestamp» = Momentaufnahme, kein Handel.
        // «riseFallRate» ist die Veränderung seit 00:00 UTC+8, nicht über 24 h — darum keine 24-h-Veränderung
    }
}
