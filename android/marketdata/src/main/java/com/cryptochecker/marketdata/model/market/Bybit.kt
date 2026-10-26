package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.MarketCategory
import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private const val BYBIT_API = "https://api.bybit.com/v5/market"

/** Bybit Spot. API v5: https://bybit-exchange.github.io/docs/v5/market/tickers */
class Bybit : BybitBase("Bybit", "spot", FuturesContractType.NONE) {
    override val key = "Bybit"
}

/** Bybit USDT-/USDC-Perpetuals (Kategorie «linear»). */
class BybitFutures : BybitBase("Bybit Futures", "linear", FuturesContractType.PERPETUAL) {
    override val key = "BybitFutures"
    override val category = MarketCategory.FUTURES
}

/**
 * Gemeinsamer Teil von Spot und Perpetuals; die Kategorie steht in jeder Adresse. Antworten haben die Form
 * {"retCode", "retMsg", "result": {"list": [...]}}. Die Paarliste fragt «limit=1000» an: linear hat über 500
 * Symbole, ohne Angabe kämen nur 500.
 */
abstract class BybitBase(
    name: String,
    private val apiCategory: String,
    private val contractType: FuturesContractType,
) : SimpleMarket(
    name = name,
    pairListUrl = "$BYBIT_API/instruments-info?category=$apiCategory&limit=1000",
    tickerUrl = "$BYBIT_API/tickers?category=$apiCategory&symbol=%1\$s",
    errorField = "retMsg",
) {

    private val perpetuals: Boolean
        get() = contractType == FuturesContractType.PERPETUAL

    // ── Einzelkurs ──

    /** `time` der Antwort ist Serverzeit, kein Handel → Zeit der Börse unbekannt (wie Binance). */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val rows = resultList(json)
        if (rows.length() == 0) throw MarketParseException(MarketParseException.NO_DATA)
        copyRow(rows.getJSONObject(0), ticker)
    }

    /** retCode 0 heisst Erfolg — «OK» ist dann kein Fehlertext; es bleibt beim Fehler des Parsers. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optInt("retCode", -1) == 0) noErrorText()
        return super.errorMessageJson(call, json, info)
    }

    // ── Sammelabfrage (blättert nicht) ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Enthält alle gehandelten Symbole der Kategorie. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$BYBIT_API/tickers?category=$apiCategory"

    /** Die Serverzeit `time` wird nicht auf die Paare übertragen (siehe [fillTickerJson]). */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        resultList(JSONObject(body)).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /**
     * instruments-info blättert mit `nextPageCursor` (höchstens 1000 je Seite); mit den TradFi-Perps kann
     * linear darüber wachsen. Leerer Cursor = letzte Seite. Eine unlesbare Seite wirft (JSONException) — still
     * aufhören hiesse, dass Paare der weiteren Seiten unbemerkt fehlen. Wie `Bybit.swift`.
     */
    override fun pairListNextPage(call: Int, body: String): String? {
        val result = JSONObject(body).optJSONObject("result") ?: return null
        val cursor = result.optString("nextPageCursor")
        if (cursor.isEmpty()) return null
        return pairListAddress(call) + "&cursor=" + bybitCursorParam(cursor)
    }

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        resultList(json).forEachJSONObject { entry ->
            if (entry.optString("status") != "Trading") return@forEachJSONObject
            // Futures: nur Perpetuals (Laufzeit-Kontrakte tragen andere Symbole)
            if (perpetuals && entry.optString("contractType") != "LinearPerpetual") return@forEachJSONObject
            // TradFi (Aktien, ETFs, Rohstoffe, Devisen) nur bei Perps; Spot-Token bleiben Token
            val tradFi = perpetuals && TradFi.bybit(entry.optString("symbolType"))
            out += MarketPair(
                entry.getString("baseCoin"),
                entry.getString("quoteCoin"),
                entry.getString("symbol"),
                contractType,
                tradFi,
            )
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.bid = row.optDoubleOrMissing("bid1Price")
        ticker.ask = row.optDoubleOrMissing("ask1Price")
        ticker.high = row.optDoubleOrMissing("highPrice24h")
        ticker.low = row.optDoubleOrMissing("lowPrice24h")
        ticker.vol = row.optDoubleOrMissing("volume24h")
        ticker.volQuote = row.optDoubleOrMissing("turnover24h")
        // gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(row.optDouble("price24hPcnt"))
    }

    /** Array `result.list` einer Bybit-Antwort. */
    private fun resultList(envelope: JSONObject): JSONArray = envelope.getJSONObject("result").getJSONArray("list")
}

/**
 * Bybit liefert `nextPageCursor` teils schon URL-kodiert («first%3DBTCUSDT%26last%3DETHUSDT») — dann so
 * anhängen, sonst kodieren. Wie `BybitBase.cursorParam` (iOS).
 */
internal fun bybitCursorParam(cursor: String): String =
    if ('%' in cursor) cursor else URLEncoder.encode(cursor, "UTF-8")
