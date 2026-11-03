package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val COINS_PH_API = "https://api.pro.coins.ph/openapi"

/** 24-h-Ticker: mit `symbol` ein Paar, ohne Parameter alle. */
private const val COINS_PH_DAY_TICKER = "$COINS_PH_API/quote/v1/ticker/24hr"

/**
 * Coins.ph (Philippinen), öffentliche API im Binance-ähnlichen Format: https://coins-docs.github.io/rest-api/ —
 * Kennung wie «BTCPHP». Anders als bei Binance ist `priceChangePercent` ein Anteil (−0.0268 = −2,68 %), darum zählt
 * `openPrice`.
 */
class CoinsPh : SimpleExchange(
    name = "Coins.ph",
    pairListUrl = "$COINS_PH_API/v1/exchangeInfo",
    tickerUrl = "$COINS_PH_DAY_TICKER?symbol=%1\$s",
    errorField = "msg",
) {
    override val key = "CoinsPh"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + info.quote.uppercase())

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Der Ticker ohne `symbol` führt jedes handelbare Paar (Aufzeichnung 03.11.2026: 95 von 95). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = COINS_PH_DAY_TICKER

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Nur Status «trading» (klein geschrieben); «break» hat keinen laufenden Handel. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("symbols").forEachJSONObject { entry ->
            if (!entry.optString("status").equals("TRADING", ignoreCase = true)) return@forEachJSONObject
            out += ExchangePair(
                entry.getText("baseAsset").uppercase(),
                entry.getText("quoteAsset").uppercase(),
                entry.getText("symbol"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Zeile des 24-h-Tickers (gleitende 24 h: `openTime` bis `closeTime`). `openPrice` = Kurs vor 24 h;
     * `closeTime` ist das Fensterende, kein Handel — die Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("openPrice"))
    }
}
