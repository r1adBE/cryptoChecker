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

private const val HASHKEY_API = "https://api-pro.hashkey.com"

/** 24-h-Ticker: mit `symbol` ein Paar, ohne Parameter alle. */
private const val HASHKEY_DAY_TICKER = "$HASHKEY_API/quote/v1/ticker/24hr"

/** `status` eines handelbaren Paars in `exchangeInfo`. */
private const val HASHKEY_STATUS_TRADING = "TRADING"

/**
 * HashKey Exchange (Hongkong, lizenziert; USD-, HKD- und Stablecoin-Paare), öffentliche Kurse ohne Schlüssel:
 * https://hashkeypro-apidoc.readme.io. Kennung «BTCUSD». Der Ticker kommt als Liste, auch für ein einzelnes Paar.
 */
class HashKey : SimpleMarket(
    name = "HashKey Exchange",
    pairListUrl = "$HASHKEY_API/api/v1/exchangeInfo",
    tickerUrl = "$HASHKEY_DAY_TICKER?symbol=%1\$s",
    ttsName = "Hash Key Exchange",
    errorField = "msg",
) {
    override val key = "HashKey"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: (info.base + info.quote)

    /** Liste mit einer Zeile (dokumentiert) oder, vorsichtshalber, ein einzelnes Objekt. Leere Liste = «No data». */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val row = if (body.trimStart().startsWith("[")) {
            val rows = JSONArray(body)
            if (rows.length() == 0) throw MarketParseException(MarketParseException.NO_DATA)
            rows.getJSONObject(0)
        } else {
            JSONObject(body)
        }
        copyRow(row, ticker)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Ob die ungefilterte Liste jedes handelbare Paar enthält, ist unbelegt: Fehlende prüft die Einzelabfrage. */
    override val bulkTickersComplete: Boolean
        get() = false

    override fun getBulkTickersUrl(call: Int): String = HASHKEY_DAY_TICKER

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optText("s"), row, "c", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("symbols").forEachJSONObject { symbol ->
            if (symbol.optText("status") != HASHKEY_STATUS_TRADING) return@forEachJSONObject
            out += MarketPair(symbol.optText("baseAsset"), symbol.optText("quoteAsset"), symbol.optText("symbol"))
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile mit Kurzfeldern (c = letzter Kurs, h/l = 24-h-Hoch/-Tief, v/qv = Volumen). `t` ist die Zeit
     * der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("c")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("b")
        ticker.ask = row.optDoubleOrMissing("a")
        ticker.high = row.optDoubleOrMissing("h")
        ticker.low = row.optDoubleOrMissing("l")
        ticker.vol = row.optDoubleOrMissing("v")
        ticker.volQuote = row.optDoubleOrMissing("qv")
        // «o»: Eröffnung des gleitenden 24-h-Fensters
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("o"))
    }
}
