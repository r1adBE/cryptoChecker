package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject

private const val GATEIO_API = "https://api.gateio.ws/api/v4/spot"

/** Ticker: mit `currency_pair` ein Paar, ohne Parameter alle. */
private const val GATEIO_TICKERS = "$GATEIO_API/tickers"

/** Gate.io Spot (API v4). Kennung = `id` der Paarliste («BTC_USDT»). */
class GateIo : SimpleMarket(
    name = "Gate.io",
    pairListUrl = "$GATEIO_API/currency_pairs",
    tickerUrl = "$GATEIO_TICKERS?currency_pair=%1\$s",
    ttsName = "Gate io",
    errorField = "message",
) {
    override val key = "GateIo"

    // ── Einzelkurs ──

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val rows = JSONArray(body)
        if (rows.length() == 0) throw MarketParseException(MarketParseException.NO_DATA)
        copyRow(rows.getJSONObject(0), ticker)
    }

    // ── Sammelabfrage: derselbe Endpunkt ohne currency_pair ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(call: Int): String = GATEIO_TICKERS

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("currency_pair"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).forEachJSONObject { entry ->
            if (entry.getString("trade_status") != "tradable") return@forEachJSONObject
            out += MarketPair(entry.getString("base"), entry.getString("quote"), entry.getString("id"))
        }
    }

    // ── Namen ──

    /** Volle Namen aus derselben Liste (`base_name`, `quote_name`), etwa «QTUM» → «Quantum». */
    override fun parseBaseNames(call: Int, body: String): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        JSONArray(body).forEachJSONObject { entry ->
            if (entry.optString("trade_status") != "tradable") return@forEachJSONObject
            remember(names, entry, "base", "base_name")
            remember(names, entry, "quote", "quote_name")
        }
        return names
    }

    // ── Lesen ──

    /** Eine Ticker-Zeile (Einzel- wie Sammelabruf). */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last")
        ticker.high = row.getDouble("high_24h")
        ticker.low = row.getDouble("low_24h")
        ticker.vol = row.getDouble("base_volume")
        ticker.volQuote = row.getDouble("quote_volume")
        // ohne Orderbuch schickt Gate "" für Geld/Brief: dann fehlen sie, statt die Zeile zu verwerfen
        ticker.bid = row.optDoubleOrMissing("highest_bid")
        ticker.ask = row.optDoubleOrMissing("lowest_ask")
        // «change_percentage»: gleitende 24 h in Prozent (change_utc0/utc8 wären Tageswerte)
        ticker.change24hPercent = Change24h.percent(row.optDouble("change_percentage"))
    }

    /** Erster Name je Kürzel gewinnt; leere Angaben (auch JSON null) zählen nicht. */
    private fun remember(names: MutableMap<String, String>, entry: JSONObject, codeField: String, nameField: String) {
        val code = entry.optString(codeField)
        val name = entry.optText(nameField)
        if (code.isEmpty() || name.isEmpty()) return
        names.putIfAbsent(code, name)
    }
}
