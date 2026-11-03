package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val BINANCE_TH_API = "https://api.binance.th/api/v1"

/**
 * Binance TH (Gulf Binance, Thailand), öffentliche API v1 im Binance-Format: https://www.binance.th/api-docs/en/ —
 * Kennung wie «BTCTHB». Der 24-h-Ticker verlangt ein Symbol (ohne Symbol HTTP 400), darum keine Sammelabfrage.
 */
class BinanceTh : SimpleExchange(
    name = "Binance TH",
    pairListUrl = "$BINANCE_TH_API/exchangeInfo",
    tickerUrl = "$BINANCE_TH_API/ticker/24hr?symbol=%1\$s",
    errorField = "msg",
) {
    override val key = "BinanceTh"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + info.quote.uppercase())

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    // ── Paarliste ──

    /** Nur Status «TRADING» (wie Binance). */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("symbols").forEachJSONObject { entry ->
            if (entry.optString("status") != "TRADING") return@forEachJSONObject
            out += ExchangePair(entry.getText("baseAsset"), entry.getText("quoteAsset"), entry.getText("symbol"))
        }
    }

    // ── Lesen ──

    /**
     * Der 24-h-Ticker wie bei Binance: `priceChangePercent` über gleitende 24 h, bereits Prozent. `closeTime` ist
     * bloss das Fensterende, kein Handel — die Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }
}
