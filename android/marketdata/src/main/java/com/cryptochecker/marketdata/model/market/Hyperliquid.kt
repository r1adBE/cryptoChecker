package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.PostRequestInfo
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

/** Die eine Adresse für Paare, Einzel- und Sammelkurse (POST). */
private const val HYPERLIQUID_INFO = "https://api.hyperliquid.xyz/info"

/** Höchstlänge der Rohantwort als Fehlertext. */
private const val HYPERLIQUID_ERROR_CHARS = 200

/**
 * Hyperliquid — dezentrale Perpetual-Börse mit USDC-Abrechnung. Eine POST-Abfrage («metaAndAssetCtxs»)
 * liefert Liste und Kurse aller Perps, darum kein `SimpleMarket`:
 * [ {universe: [{name, …}, …]}, [{markPx, midPx, prevDayPx, dayNtlVlm, dayBaseVlm, …}, …] ],
 * beide Teile über den Index verbunden.
 *
 * Kurs = `midPx` (Mitte Geld/Brief, ersatzweise `markPx`): kein letzter Handel, keine Zeit der Börse — daher
 * [priceIsMid] («Mittelkurs»), Frische ab Abruf. Die 24-h-Veränderung ist Mark gegen Mark ([markChange]).
 * Letzte Handel gäbe es nur je Coin (`recentTrades`) — für alle Perps zu teuer.
 */
class Hyperliquid : Market("Hyperliquid", "Hyperliquid") {
    override val key = "Hyperliquid"

    override val priceIsMid: Boolean
        get() = true

    /** Veränderung Mark gegen Mark ([markChange]) — als «Mark» benannt. */
    override val changeIsMark: Boolean
        get() = true

    // ── Einzelkurs: dieselbe Antwort, daraus der gesuchte Coin ──

    override fun tickerAddress(call: Int, info: PairQuery): String = HYPERLIQUID_INFO

    // Für jedes Paar dieselbe Abfrage (~100 KB): gleichzeitige Einzelkurse teilen sich eine Antwort (InFlightRequests)
    override fun tickerPostBody(call: Int, info: PairQuery): PostRequestInfo = metaRequest()

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val coin = info.pairId
        val snapshot = readUniverse(body)[coin] ?: throw MarketParseException("Unknown coin: $coin")
        ticker.last = snapshot.last
        ticker.vol = snapshot.vol
        ticker.volQuote = snapshot.volQuote
        ticker.change24hPercent = snapshot.change24hPercent
        ticker.markFallback = snapshot.markFallback
    }

    /**
     * Hyperliquid antwortet bei Fehlern mit blossem Text: dessen Anfang ist der Fehlertext. Eine gültige Antwort
     * (JSON-Liste) ohne den Coin hat keinen — sonst verdeckte ihr Anfang «Unknown coin» (NotTraded.isMissingEntry).
     */
    override fun errorMessage(call: Int, body: String, info: PairQuery): String? {
        if (body.trimStart().startsWith("[")) noErrorText()
        return body.take(HYPERLIQUID_ERROR_CHARS)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = HYPERLIQUID_INFO

    override fun getBulkTickersPostRequestInfo(call: Int): PostRequestInfo = metaRequest()

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        tickers.putAll(readUniverse(body, failed))
    }

    // ── Paarliste ──

    override fun pairListAddress(call: Int): String = HYPERLIQUID_INFO

    override fun pairListPostBody(call: Int): PostRequestInfo = metaRequest()

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).getJSONObject(0).getJSONArray("universe").forEachJSONObject { coin ->
            if (coin.optBoolean("isDelisted")) return@forEachJSONObject
            val name = coin.getString("name")
            out += MarketPair(name, "USDC", name, FuturesContractType.PERPETUAL)
        }
    }

    // ── Lesen ──

    /** Ein Kontext; bei dünnem Orderbuch fehlt `midPx` → Mark-Preis, als solcher markiert ([Ticker.markFallback]). */
    private fun copyRow(context: JSONObject, ticker: Ticker) {
        val mid = context.optDouble("midPx", Double.NaN)
        ticker.markFallback = mid.isNaN()
        ticker.last = if (mid.isNaN()) context.optDouble("markPx", Double.NaN) else mid
        ticker.vol = context.optDoubleOrMissing("dayBaseVlm")
        ticker.volQuote = context.optDoubleOrMissing("dayNtlVlm")
        ticker.change24hPercent = markChange(context)
    }

    /** Die eine Abfrage für Paare, Einzel- und Sammelkurse. */
    private fun metaRequest(): PostRequestInfo =
        PostRequestInfo(body = """{"type":"metaAndAssetCtxs"}""", headers = mapOf("Content-Type" to "application/json"))

    /**
     * Alle gelisteten Coins mit Kurs; [failed] sammelt Coins, deren Kontext keinen lesbaren Preis hat. Universum
     * und Kontexte hängen über den Index zusammen, darum hier eine Zählschleife statt `forEachJSONObject`.
     */
    private fun readUniverse(body: String, failed: MutableSet<String> = HashSet()): Map<String, Ticker> {
        val root = JSONArray(body)
        val universe = root.getJSONObject(0).getJSONArray("universe")
        val contexts = root.getJSONArray(1)
        val byCoin = HashMap<String, Ticker>()
        val count = minOf(universe.length(), contexts.length())
        for (index in 0 until count) {
            val coin = universe.getJSONObject(index)
            if (coin.optBoolean("isDelisted")) continue
            val context = contexts.optJSONObject(index) ?: continue
            if (withoutPrice(context, coin)) continue
            bulkRow(coin.getString("name"), byCoin, failed) { copyRow(context, it) }
        }
        return byCoin
    }

    /**
     * Kontext ohne Preis auslassen statt als Fehler zählen: midPx/markPx vorhanden, aber leer (ruhend), oder
     * beide fehlen bei einem Coin ohne Namen. Fehlen beide bei benanntem Coin → Fehler über [bulkRow].
     */
    private fun withoutPrice(context: JSONObject, coin: JSONObject): Boolean {
        val hasMid = context.has("midPx")
        val hasMark = context.has("markPx")
        val usableMid = hasMid && !BulkTickerRows.inactivePrice(context, "midPx")
        val usableMark = hasMark && !BulkTickerRows.inactivePrice(context, "markPx")
        return when {
            usableMid || usableMark -> false
            hasMid || hasMark -> true
            else -> coin.optString("name").isEmpty()
        }
    }

    /**
     * 24-h-Veränderung Mark gegen Mark: `prevDayPx` ist ein Mark-Preis von vor 24 h, einen damaligen
     * Mittelkurs gibt es nicht. Mittelkurs gegen Mark vermischte den Abstand Mid/Mark mit der Bewegung.
     * Ohne `markPx` keine Angabe (dann Kerzen).
     */
    private fun markChange(context: JSONObject): Double? =
        Change24h.fromOpen(context.optDouble("markPx", Double.NaN), context.optDouble("prevDayPx"))
}
