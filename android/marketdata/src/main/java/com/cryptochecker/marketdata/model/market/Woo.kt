package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONObject

private const val WOO_API = "https://api.woox.io"

/** Spot-Kerzen je Abruf: die laufende Stunde plus 24 volle (Fenster bis jetzt). */
private const val WOO_HOURS = 25

/** Grund, wenn die Kerzenliste leer ist. */
private const val WOO_NO_CANDLES = "Keine Kerzen"

/**
 * WOO X Spot (API v3, developer.woox.io).
 *
 * Spot hat keinen 24-h-Ticker; Grundlage sind die letzten [WOO_HOURS] Stundenkerzen. Letzter Kurs = Schluss der
 * jüngsten Kerze. Das 24-h-Fenster endet jetzt (Antwortzeit `timestamp` der Hülle):
 *  - jüngste Kerze ist die laufende Stunde → 24 volle Stunden plus die angebrochene (alle 25);
 *  - jüngste Kerze schon abgeschlossen (laufende Stunde fehlt noch) → genau die 24 vollen davor;
 *  - ohne Antwortzeit gilt die jüngste Kerze als laufende Stunde.
 * Hoch, Tief und Volumen über dieses Fenster; Veränderung gegen die Eröffnung seiner ältesten Kerze, aber nur bei
 * lückenlosem Fenster. Liegt die jüngste Kerze mehr als eine Stunde zurück, endet kein Fenster jetzt: keine
 * Veränderung (dann rechnet die App mit eigenen Kerzen). Gegenstück: `Woo.swift`.
 */
class Woo : WooBase(
    name = "WOO X",
    symbolPrefix = "SPOT",
    contractType = FuturesContractType.NONE,
    tickerUrl = "$WOO_API/v3/public/kline?symbol=%1\$s&type=1h&limit=$WOO_HOURS",
) {
    override val key = "Woo"

    /** Kurs = Schluss der jüngsten Stundenkerze: im Aktionsblatt «Stundenkurs». */
    override val priceIsHourlyClose: Boolean
        get() = true

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val candles = ArrayList<JSONObject>()
        json.getJSONObject("data").getJSONArray("rows").forEachJSONObject { candles.add(it) }
        // Neueste zuerst, je Stundenbeginn nur eine Kerze
        val hourly = candles.sortedByDescending { it.start() }.distinctBy { it.start() }
        val newest = hourly.firstOrNull() ?: throw MarketParseException(WOO_NO_CANDLES)
        val last = newest.getDouble("close")
        ticker.last = last

        // Antwortzeit (kein Handelszeitpunkt, Zeit der Börse bleibt offen) — nur für die Lage des Fensters
        val answeredAt = json.optLong("timestamp").takeIf { it > 0 }
        val newestAge = answeredAt?.minus(newest.start())
        val newestIsRunning = newestAge == null || newestAge < Change24h.HOUR_MILLIS
        val span = if (newestIsRunning) WOO_HOURS else WOO_HOURS - 1
        val window = hourly.take(span)

        window.map { it.optDouble("high") }.filterNot { it.isNaN() }.maxOrNull()?.let { ticker.high = it }
        window.map { it.optDouble("low") }.filterNot { it.isNaN() }.minOrNull()?.let { ticker.low = it }
        ticker.vol = window.sumOf { it.optDouble("volume", 0.0) }
        ticker.volQuote = window.sumOf { it.optDouble("amount", 0.0) }

        // Eröffnung der ältesten Kerze ≈ Kurs vor 24 h — nur lückenlos und wenn das Fenster jetzt endet
        val endsNow = newestAge == null || newestAge < 2 * Change24h.HOUR_MILLIS
        val firstHour = if (endsNow) Change24h.hourlyWindowStart(window.map { it.start() }, span) else null
        val opening = firstHour?.let { start -> window.firstOrNull { it.start() == start } }
        ticker.change24hPercent = opening?.let { Change24h.fromOpen(last, it.optDouble("open")) }
    }

    // ── Lesen ──

    private fun JSONObject.start(): Long = optLong("startTimestamp")
}

/** WOO X USDT-Perpetuals. */
class WooFutures : WooBase(
    name = "WOO X Futures",
    symbolPrefix = "PERP",
    contractType = FuturesContractType.PERPETUAL,
    tickerUrl = "$WOO_API/v3/public/futures?symbol=%1\$s",
) {
    override val key = "WooFutures"

    // ── Einzelkurs ──

    /** Hüllenzeit = Antwortzeit, kein Handel: Zeit der Börse bleibt offen. */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json.getJSONObject("data").getJSONArray("rows").getJSONObject(0), ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$WOO_API/v3/public/futures"

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONObject("data").getJSONArray("rows").forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "24hClose", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val close = row.getDouble("24hClose")
        ticker.last = close
        ticker.high = row.optDoubleOrMissing("24hHigh")
        ticker.low = row.optDoubleOrMissing("24hLow")
        ticker.vol = row.optDoubleOrMissing("24hVolume")
        ticker.volQuote = row.optDoubleOrMissing("24hAmount")
        // «24hOpen» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(close, row.optDouble("24hOpen"))
    }
}

/** Gemeinsam: Instrumentliste, Kennung «PRÄFIX_BASIS_QUOTE», Fehlertext in «message». */
abstract class WooBase(
    name: String,
    private val symbolPrefix: String,
    private val contractType: FuturesContractType,
    tickerUrl: String,
) : SimpleMarket(
    name = name,
    pairListUrl = "$WOO_API/v3/public/instruments",
    tickerUrl = tickerUrl,
    ttsName = "Woo X",
    errorField = "message",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: listOf(symbolPrefix, info.base, info.quote).joinToString("_")

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONObject("data").getJSONArray("rows").forEachJSONObject { inst ->
            if (inst.optString("status") != "TRADING") return@forEachJSONObject
            val symbol = inst.optString("symbol")
            // SPOT_BTC_USDT bzw. PERP_BTC_USDT
            val parts = symbol.split('_')
            if (parts.size != 3 || parts[0] != symbolPrefix) return@forEachJSONObject
            out += MarketPair(
                inst.optString("baseAsset").ifEmpty { parts[1] },
                inst.optString("quoteAsset").ifEmpty { parts[2] },
                symbol,
                contractType,
            )
        }
    }
}
