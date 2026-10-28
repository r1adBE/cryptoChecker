package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val LUNO_API = "https://api.luno.com"

/** Luno führt Bitcoin als «XBT»; angezeigt und verglichen wird das übliche Kürzel. */
private val LUNO_COMMON = mapOf("XBT" to "BTC")

/**
 * Status, bei denen ein Markt gehandelt wird. POST_ONLY (Ticker: «POSTONLY») nimmt nur Limit-Aufträge an, der
 * Kurs bleibt aber gültig; DISABLED zeigt bloss den letzten Handel vor der Sperre.
 */
private val LUNO_TRADING = setOf("ACTIVE", "POST_ONLY", "POSTONLY")

/**
 * Luno (Südafrika, Nigeria, Malaysia u. a.), öffentliche API: https://www.luno.com/en/developers/api — Kennung
 * wie «XBTZAR». Ticker ohne Hoch/Tief und ohne 24-h-Veränderung (die App rechnet mit Kerzen).
 */
class Luno : SimpleExchange(
    name = "Luno",
    pairListUrl = "$LUNO_API/api/exchange/1/markets",
    tickerUrl = "$LUNO_API/api/1/ticker?pair=%1\$s",
    errorField = "error",
) {
    override val key = "Luno"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (lunoCode(info.base) + lunoCode(info.quote))

    /** Gesperrter Markt: kein Kurs (sonst stünde der letzte Handel vor der Sperre als aktuell da). */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        if (!isTrading(json)) throw ExchangeParseException(ExchangeParseException.NO_DATA)
        copyRow(json, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * `tickers` führt jeden handelbaren Markt (Aufzeichnung 19.10.2026: 147 von 147 Märkten der Paarliste),
     * ein fehlendes Paar ist nicht mehr gelistet.
     */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$LUNO_API/api/1/tickers"

    /** Gesperrte Märkte fallen still weg und gelten damit als nicht mehr gehandelt ([bulkTickersComplete]). */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("tickers").forEachJSONObject { row ->
            if (!isTrading(row)) return@forEachJSONObject
            bulkRowIfActive(row.optText("pair"), row, "last_trade", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONArray("markets").forEachJSONObject { market ->
            if (market.optString("trading_status") !in LUNO_TRADING) return@forEachJSONObject
            out += ExchangePair(
                common(market.getText("base_currency")),
                common(market.getText("counter_currency")),
                market.getText("market_id"),
            )
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile. Preise und Volumen kommen als Text; `rolling_24_hour_volume` zählt in der Basiswährung.
     * `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_trade")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.vol = row.optDoubleOrMissing("rolling_24_hour_volume")
    }

    /** Ohne Statusfeld (oder JSON null) gilt der Markt als gehandelt; Fehlerantworten tragen keinen Status. */
    private fun isTrading(row: JSONObject): Boolean = row.isNull("status") || row.optString("status") in LUNO_TRADING

    private fun common(code: String): String = code.uppercase().let { LUNO_COMMON[it] ?: it }

    /** Umkehrung von [common] für die Kennung ohne gespeicherte Paar-ID. */
    private fun lunoCode(code: String): String =
        LUNO_COMMON.entries.firstOrNull { it.value == code.uppercase() }?.key ?: code.uppercase()
}
