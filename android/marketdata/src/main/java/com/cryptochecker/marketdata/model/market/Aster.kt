package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val ASTER_FAPI = "https://fapi.asterdex.com/fapi/v1"

/**
 * Aster — dezentrale Perpetual-Börse mit öffentlicher Schnittstelle im Aufbau von Binance USDⓈ-M (fapi):
 * `exchangeInfo` und `ticker/24hr` (mit `symbol` ein Objekt, ohne alle als Array). Der Kurs ist der letzte
 * Handel (`lastPrice`), kein Mark-Preis — darum weder Mittelkurs- noch Mark-Kennzeichnung wie bei Hyperliquid.
 */
class Aster : SimpleMarket(
    name = "Aster",
    pairListUrl = "$ASTER_FAPI/exchangeInfo",
    tickerUrl = "$ASTER_FAPI/ticker/24hr?symbol=%1\$s",
    errorField = "msg",
) {
    override val key = "Aster"

    // ── Einzelkurs ──

    /** Unbekanntes Symbol: {"code":…,"msg":…} — ohne `lastPrice` scheitert das Lesen, «msg» wird Fehlertext. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Ungefiltert enthält die Antwort alle gehandelten Kontrakte. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$ASTER_FAPI/ticker/24hr"

    /** Einträge, die keine Objekte sind, übergehen; unlesbare Zahlen treffen nur ihr Paar. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachOptJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /**
     * Nur handelbare Perpetuals; fehlen «contractType» oder «status», gilt der Eintrag als handelbares Perpetual
     * (Aster führt nur solche).
     */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("symbols").forEachJSONObject { entry ->
            val perpetual = entry.optText("contractType").ifEmpty { "PERPETUAL" } == "PERPETUAL"
            val trading = entry.optText("status").ifEmpty { "TRADING" } == "TRADING"
            if (!perpetual || !trading) return@forEachJSONObject
            out += MarketPair(
                entry.getString("baseAsset"),
                entry.getString("quoteAsset"),
                entry.getString("symbol"),
                FuturesContractType.PERPETUAL,
            )
        }
    }

    // ── Lesen ──

    /** `closeTime` ist nur das Fensterende, kein Handel: die Zeit der Börse bleibt unbekannt (wie Binance Futures). */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // gleitende 24 h, bereits Prozent (wie Binance)
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }
}
