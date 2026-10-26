package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.MarketCategory
import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.getText
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val BINGX_API = "https://open-api.bingx.com/openApi"

/** «status» eines handelbaren Symbols bzw. Kontrakts (1 = online). */
private const val BINGX_ONLINE = 1

/** BingX Spot. API: https://bingx-api.github.io/docs/#/en-us/spot/market-api.html */
class BingX : BingXBase(
    name = "BingX",
    contractType = FuturesContractType.NONE,
    pairListPath = "/spot/v1/common/symbols",
    tickerPath = "/spot/v1/ticker/24hr",
) {
    override val key = "BingX"
}

/** BingX USDT-Perpetuals (Swap V2). API: https://bingx-api.github.io/docs/#/en-us/swapV2/market-api.html */
class BingXFutures : BingXBase(
    name = "BingX Futures",
    contractType = FuturesContractType.PERPETUAL,
    pairListPath = "/swap/v2/quote/contracts",
    tickerPath = "/swap/v2/quote/ticker",
) {
    override val key = "BingXFutures"
    override val category = MarketCategory.FUTURES
}

/**
 * Gemeinsamer Teil von Spot und Perpetuals. Antworten haben die Form {"code", "msg", "data"}; `data` ist beim
 * Einzelkurs je nach Bereich ein Objekt (Swap) oder ein Array mit einem Eintrag (Spot), bei der Sammelabfrage ein
 * Array — [dataRows] nimmt beides. Kennung beider Bereiche: «BTC-USDT».
 *
 * Die Doku verlangt auch bei öffentlichen Abfragen den Parameter `timestamp` (ms); ohne ihn können Abfragen
 * abgelehnt werden. Darum kein `SimpleMarket`: jede Adresse wird beim Abruf gestempelt ([stamped]).
 */
abstract class BingXBase(
    name: String,
    private val contractType: FuturesContractType,
    private val pairListPath: String,
    private val tickerPath: String,
) : Market(name, name) {

    private val perpetuals: Boolean
        get() = contractType == FuturesContractType.PERPETUAL

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String =
        stamped("$BINGX_API$tickerPath?symbol=${info.pairId}")

    /** `closeTime` ist das Fensterende (≈ jetzt), kein Handel → Zeit der Börse unbekannt (wie MEXC). */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val rows = dataRows(json)
        if (rows.isEmpty()) throw MarketParseException(MarketParseException.NO_DATA)
        copyRow(rows[0], ticker)
    }

    /** code 0 heisst Erfolg — dann ist «msg» (meist leer) kein Fehlertext; es bleibt beim Fehler des Parsers. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (json.optInt("code", -1) == 0) noErrorText()
        return json.getText("msg")
    }

    // ── Sammelabfrage (ohne `symbol` alle Paare des Bereichs) ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = stamped("$BINGX_API$tickerPath")

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        dataRows(JSONObject(body)).forEach { row ->
            bulkRowIfActive(row.optText("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun pairListAddress(call: Int): String = stamped("$BINGX_API$pairListPath")

    /**
     * Spot: {"data": {"symbols": [{symbol, status}]}} — Basis und Kotierung nur im Symbol. Swap: {"data":
     * [{symbol, asset, currency, status}]}. Nur «status» 1 (online); offline, Voreröffnung und ausgesetzte nicht.
     * Swap-TradFi ([TradFi.bingx]): `asset` ist eine interne Kennung («NCSKAAPL2USD»), die Basis kommt aus
     * `displayName`; Krypto behält `asset` (`displayName` weicht dort ab, etwa NEIROCTO → «NEIRO»).
     */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        val entries = if (perpetuals) json.getJSONArray("data") else json.getJSONObject("data").getJSONArray("symbols")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.optInt("status", -1) != BINGX_ONLINE) continue
            val symbol = entry.getString("symbol")
            if (perpetuals) {
                val asset = entry.getString("asset")
                val tradFi = TradFi.bingx(asset)
                val base = if (tradFi) TradFi.bingxBase(entry.optText("displayName"), asset) else asset
                out += MarketPair(base, entry.getString("currency"), symbol, contractType, tradFi)
            } else {
                // Kotierung = Teil nach dem letzten «-» (Kotierungen enthalten keinen Bindestrich)
                val cut = symbol.lastIndexOf('-')
                if (cut <= 0 || cut == symbol.length - 1) continue
                out += MarketPair(symbol.substring(0, cut), symbol.substring(cut + 1), symbol)
            }
        }
    }

    // ── Lesen ──

    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("lastPrice")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bidPrice")
        ticker.ask = row.optDoubleOrMissing("askPrice")
        ticker.high = row.optDoubleOrMissing("highPrice")
        ticker.low = row.optDoubleOrMissing("lowPrice")
        ticker.vol = row.optDoubleOrMissing("volume")
        ticker.volQuote = row.optDoubleOrMissing("quoteVolume")
        // Gleitende 24 h aus «openPrice»: «priceChangePercent» kommt je Bereich verschieden (Spot mit «%»)
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("openPrice"))
    }

    /** Einträge von `data`: Array → alle (jeder muss ein Objekt sein), Objekt → dieses eine. */
    private fun dataRows(envelope: JSONObject): List<JSONObject> {
        val data: Any = envelope.opt("data") ?: throw JSONException("No value for data")
        return when (data) {
            is JSONArray -> List(data.length()) { data.getJSONObject(it) }
            is JSONObject -> listOf(data)
            else -> throw JSONException("No value for data")
        }
    }

    /** Hängt `timestamp` (jetzt, ms) an; die Börse prüft ihn gegen ihre Uhr. */
    private fun stamped(url: String): String =
        url + (if ('?' in url) "&" else "?") + "timestamp=" + System.currentTimeMillis()
}
