package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

/**
 * Binance TR (Türkei), öffentliche «open API» wie Tokocrypto: https://www.binance.tr/apidocs/ — ausserhalb der
 * Türkei antwortet die Börse mit HTTP 451 (Standort gesperrt). Paare des Typs 1 laufen über `api.binance.me`.
 */
class BinanceTr : BinanceCloudBase(
    name = "Binance TR",
    host = "https://www.binance.tr",
    binanceApi = "https://api.binance.me/api/v3",
) {
    override val key = "BinanceTr"
}

/**
 * Gemeinsamer Aufbau von Binance TR und Tokocrypto («Binance Cloud»): Paarliste `open/v1/common/symbols`
 * (`data.list`, Kennung «BTC_TRY»). Eigene Märkte (Typ ≠ 1) liefern den Kurs über den letzten Handel
 * (`open/v1/market/trades`, Antwort `{"code": 0, "data": [...]}`), ohne 24-h-Werte (die App rechnet mit Kerzen).
 * Märkte des Typs 1 führt das Binance-Orderbuch [binanceApi] — ihre Kennung ist ohne «_» («BTCUSDT»), der Kurs
 * kommt vom 24-h-Ticker im Binance-Format. Keine Sammelabfrage.
 */
abstract class BinanceCloudBase(
    name: String,
    private val host: String,
    private val binanceApi: String,
) : SimpleExchange(
    name = name,
    pairListUrl = "$host/open/v1/common/symbols",
    tickerUrl = "$host/open/v1/market/trades?symbol=%1\$s&limit=1",
    errorField = "msg",
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base.uppercase() + "_" + info.quote.uppercase())

    /** Eigener Markt («BTC_TRY»): letzter Handel; Typ 1 («BTCUSDT»): 24-h-Ticker des Binance-Orderbuchs. */
    override fun tickerAddress(call: Int, info: PairQuery): String {
        val id = pairId(info)
        return if ('_' in id) super.tickerAddress(call, info) else "$binanceApi/ticker/24hr?symbol=$id"
    }

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        if ('_' in pairId(info)) readLastTrade(body, ticker) else readBinanceDay(JSONObject(body), ticker)
    }

    /** `{"code": ≠0, "msg": …}`; Code 0 (oder ohne Code) ist kein Fehler. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optInt("code", 0) == 0) noErrorText()
        return super.errorMessageJson(call, json, info)
    }

    // ── Paarliste ──

    /** Nur `spotTradingEnable` «1»; Typ 1 bekommt die Kennung des Binance-Orderbuchs (ohne «_»). */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        val data = json.get("data")
        val rows = if (data is JSONObject) data.getJSONArray("list") else data as JSONArray
        rows.forEachJSONObject { market ->
            if (market.optString("spotTradingEnable") != "1") return@forEachJSONObject
            val base = market.getText("baseAsset").uppercase()
            val quote = market.getText("quoteAsset").uppercase()
            val id = if (market.optString("type") == "1") base + quote else market.getText("symbol")
            out += ExchangePair(base, quote, id)
        }
    }

    // ── Lesen ──

    /** Letzter Handel: `data` als Liste (oder `data.list`), der jüngste Eintrag zählt; `time` = Zeit des Handels. */
    private fun readLastTrade(body: String, ticker: Ticker) {
        val data = JSONObject(body).get("data")
        val rows = if (data is JSONObject) data.getJSONArray("list") else data as JSONArray
        if (rows.length() == 0) throw ExchangeParseException(ExchangeParseException.NO_DATA)
        val row = rows.getJSONObject(rows.length() - 1)
        ticker.last = row.getDouble("price")
        ticker.timestamp = row.optLong("time")
    }

    /** 24-h-Ticker wie Binance: `priceChangePercent` gleitend über 24 h, bereits Prozent; `closeTime` ist kein Handel. */
    private fun readBinanceDay(row: JSONObject, ticker: Ticker) {
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
