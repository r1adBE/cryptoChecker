package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Ab so vielen Symbolen kostet die Auswahl bei Binance gleich viel wie die ganze Liste. */
private const val BINANCE_SYMBOL_LIMIT = 100

/** Erste Alpha-Teilanfrage der Sammelabfrage (davor: Spot); zugleich die Gruppe der Alpha-Paare. */
private const val BINANCE_FIRST_ALPHA_CALL = 1

/**
 * Paarliste von binance.com nur mit handelbaren Paaren und ohne die Berechtigungslisten je Paar — ein Bruchteil
 * der vollen Antwort. Binance.US dokumentiert beide Parameter nicht, dort bleibt die volle Liste.
 */
private const val BINANCE_COM_PAIR_LIST_QUERY = "?symbolStatus=TRADING&showPermissionSets=false"

/** Binance Spot (binance.com). */
class Binance : BinanceBase("Binance", "com", pairListQuery = BINANCE_COM_PAIR_LIST_QUERY) {
    override val key = "Binance"
}

/** Binance.US — gleiche API unter eigener Domain. */
class BinanceUs : BinanceBase("Binance.US", "us") {
    override val key = "BinanceUs"
}

/**
 * Gemeinsamer Teil von Binance und Binance.US (API v3). Unter «Binance» stehen zusätzlich die
 * Alpha-Paare ([BinanceAlpha], Kennung «ALPHA_<id>USDT») mit eigenen Endpunkten. [pairListQuery] hängt
 * an die Paarliste; der Status wird beim Lesen trotzdem geprüft (Binance.US filtert nicht).
 */
abstract class BinanceBase(name: String, domain: String, pairListQuery: String = "") : SimpleMarket(
    name = name,
    pairListUrl = "https://api.binance.$domain/api/v3/exchangeInfo$pairListQuery",
    tickerUrl = "https://api.binance.$domain/api/v3/ticker/24hr?symbol=%1\$s",
    errorField = "msg",
) {

    /** 24-h-Ticker: mit `symbol` ein Paar, ohne Parameter alle. */
    private val dayTicker = "https://api.binance.$domain/api/v3/ticker/24hr"

    // ── Einzelkurs ──

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val pairId = info.pairId
        if (BinanceAlpha.isPairId(pairId)) return BinanceAlpha.TICKER_URL + pairId
        return super.tickerAddress(call, info)
    }

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        if (BinanceAlpha.isPairId(info.pairId)) {
            BinanceAlpha.readTicker(body, ticker)
        } else {
            super.fillTicker(call, body, ticker, info)
        }
    }

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        copyRow(json, ticker)
    }

    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        if (BinanceAlpha.isPairId(info.pairId)) return BinanceAlpha.errorText(json)
        return super.errorMessageJson(call, json, info)
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Der ungefilterte Spot-Ticker enthält jedes gehandelte Paar. */
    override val bulkTickersComplete: Boolean
        get() = true

    /** Ohne Auswahl: alle Spot-Paare; Alpha-Teilanfragen gibt es nur mit Auswahl. */
    override fun getBulkTickersUrl(call: Int): String? = dayTicker.takeIf { call < BINANCE_FIRST_ALPHA_CALL }

    /**
     * Anfrage 0: Spot, mit `symbols=[…]` nur die beobachteten Paare (wenige KB statt rund 2 MB). Ab
     * [BINANCE_SYMBOL_LIMIT] Paaren wäre die Auswahl gleich teuer wie alles. Gibt es nur Alpha-Paare, entfällt
     * die Spot-Anfrage. Ab Anfrage 1: Alpha (Token-Liste bzw. Einzel-Ticker).
     */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        if (call >= BINANCE_FIRST_ALPHA_CALL) {
            return BinanceAlpha.bulkRequestUrls(pairIds).getOrNull(call - BINANCE_FIRST_ALPHA_CALL)
        }
        val spot = pairIds.filter { !BinanceAlpha.isPairId(it) }
        return when {
            spot.isEmpty() && pairIds.isNotEmpty() -> null
            spot.isEmpty() || spot.size > BINANCE_SYMBOL_LIMIT -> dayTicker
            else -> {
                val list = spot.joinToString(",", "[", "]") { "\"$it\"" }
                dayTicker + "?symbols=" + URLEncoder.encode(list, "UTF-8")
            }
        }
    }

    /** Spot-Anfrage plus die Alpha-Anfragen ([BinanceAlpha.bulkRequestUrls]). */
    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int =
        BinanceAlpha.bulkRequestUrls(pairIds).size + 1

    /** Spot und Alpha zählen getrennt als «vollständig» — eine gescheiterte Alpha-Anfrage trübt Spot nicht. */
    override fun bulkTickersRequestGroup(call: Int): Int =
        if (call < BINANCE_FIRST_ALPHA_CALL) 0 else BINANCE_FIRST_ALPHA_CALL

    override fun bulkTickersPairGroup(pairId: String): Int =
        if (BinanceAlpha.isPairId(pairId)) BINANCE_FIRST_ALPHA_CALL else 0

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        when {
            call == BINANCE_FIRST_ALPHA_CALL -> readAlphaList(body, tickers, failed)
            call > BINANCE_FIRST_ALPHA_CALL -> readAlphaTicker(body, tickers)
            else -> readSpotList(body, tickers, failed)
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("symbols").forEachJSONObject { entry ->
            if (entry.getString("status") != "TRADING") return@forEachJSONObject
            out += MarketPair(entry.getString("baseAsset"), entry.getString("quoteAsset"), entry.getString("symbol"))
        }
    }

    // ── Lesen ──

    /**
     * Ein Eintrag des 24-h-Tickers (Einzel- wie Sammelabruf). `closeTime` ist bloss das Fensterende,
     * kein Handel: die Zeit der Börse bleibt unbekannt, sonst wirkte ein ruhiges Paar frisch.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("lastPrice")
        ticker.high = row.getDouble("highPrice")
        ticker.low = row.getDouble("lowPrice")
        ticker.bid = row.getDouble("bidPrice")
        ticker.ask = row.getDouble("askPrice")
        ticker.vol = row.getDouble("volume")
        ticker.volQuote = row.getDouble("quoteVolume")
        // «priceChangePercent»: gleitende 24 h, bereits Prozent
        ticker.change24hPercent = Change24h.percent(row.optDouble("priceChangePercent"))
    }

    private fun readSpotList(body: String, tickers: MutableMap<String, Ticker>, failed: MutableSet<String>) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("symbol"), row, "lastPrice", tickers, failed) { copyRow(row, it) }
        }
    }

    /**
     * Alpha-Token-Liste: Token ohne Kurs (0 bzw. leer) werden nicht gehandelt und fallen still weg;
     * fehlt `price` ganz oder ist er unlesbar, gilt die Zeile als Fehler ([bulkRow]).
     */
    private fun readAlphaList(body: String, tickers: MutableMap<String, Ticker>, failed: MutableSet<String>) {
        BinanceAlpha.listRows(body).forEachOptJSONObject { row ->
            val pairId = BinanceAlpha.listRowPairId(row) ?: return@forEachOptJSONObject
            val price = row.optDouble("price")
            val tradable = price.isFinite() && price > 0.0
            if (!tradable && !row.isNull("price")) return@forEachOptJSONObject
            bulkRow(pairId, tickers, failed) { target -> check(BinanceAlpha.readListRow(row, target)) }
        }
    }

    /** Einzel-Ticker eines Alpha-Paars; ein unbekanntes Paar fehlt einfach. */
    private fun readAlphaTicker(body: String, tickers: MutableMap<String, Ticker>) {
        val single = SimpleTicker()
        val pairId = BinanceAlpha.readBulkTicker(body, single)
        if (pairId != null) tickers[pairId] = single
    }
}
