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
import org.json.JSONObject

private const val BITOPRO_API = "https://api.bitopro.com/v3"

/**
 * BitoPro (Taiwan), öffentliche API v3: https://github.com/bitoex/bitopro-offical-api-docs — Kennung wie «btc_twd»
 * (klein). Kurse unter `data` (Einzelkurs ein Objekt, Sammelabfrage eine Liste), ohne Gebot/Angebot.
 */
class BitoPro : SimpleExchange(
    name = "BitoPro",
    pairListUrl = "$BITOPRO_API/provisioning/trading-pairs",
    tickerUrl = "$BITOPRO_API/tickers/%1\$s",
    errorField = "error",
) {
    override val key = "BitoPro"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + "_" + info.quote).lowercase()

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data"), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Alle Paare in einer Antwort (Aufzeichnung 03.11.2026: 35 von 35 der Paarliste). */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BITOPRO_API/tickers"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("data").forEachJSONObject { row ->
            bulkRowIfActive(row.optText("pair"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /** Paare in Wartung (`maintain` true) fallen weg. */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("data").forEachJSONObject { market ->
            if (market.optBoolean("maintain", false)) return@forEachJSONObject
            out += ExchangePair(
                market.getText("base").uppercase(),
                market.getText("quote").uppercase(),
                market.getText("pair"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile (Zahlen als Text): Hoch/Tief und Volumen (Basiswährung) über 24 h, `priceChange24hr` =
     * Veränderung über 24 h in Prozent. Keine Zeit des letzten Handels.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.high = row.optDoubleOrMissing("high24hr")
        ticker.low = row.optDoubleOrMissing("low24hr")
        ticker.vol = row.optDoubleOrMissing("volume24hr")
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChange24hr"))
    }
}
