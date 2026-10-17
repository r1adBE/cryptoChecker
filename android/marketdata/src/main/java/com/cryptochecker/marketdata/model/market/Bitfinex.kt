package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.BulkPairChunks
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONArray
import com.cryptochecker.marketdata.util.forEachString
import org.json.JSONArray

private const val BITFINEX_API = "https://api-pub.bitfinex.com/v2"

/** Sammel-Ticker; dahinter «ALL» oder die Symbole mit Komma. */
private const val BITFINEX_TICKERS = "$BITFINEX_API/tickers?symbols="

/**
 * Bitfinex-Kürzel → gebräuchliches Kürzel, damit die Suche («ATOM») trifft. Nur Einträge, die Bitfinex
 * selbst so zuordnet (`/v2/conf/pub:map:currency:sym`). Absichtlich nicht: XCH (dort CryptoFranc, sonst
 * Chia), DOG und andere unklare. Wie `Bitfinex.swift`.
 */
private val BITFINEX_ALIASES: Map<String, String> = hashMapOf(
    "UST" to "USDT",
    "UDC" to "USDC",
    "TSD" to "TUSD",
    "EUS" to "EURS",
    "ALG" to "ALGO",
    // Ampleforth, nicht der Token «AMP»
    "AMP" to "AMPL",
    "ATO" to "ATOM",
    "DAT" to "DATA",
    "DSH" to "DASH",
    "IOT" to "IOTA",
    "MNA" to "MANA",
    // Optimism (Bitfinex ordnet OPX selbst OP zu)
    "OPX" to "OP",
    "QSH" to "QASH",
    "QTM" to "QTUM",
    "SNG" to "SNGLS",
    "STJ" to "STORJ",
    "WBT" to "WBTC",
    "YYW" to "YOYOW",
)

/**
 * Bitfinex Spot (API v2, öffentlich). Antworten sind Arrays; ein Ticker lautet
 * [BID, BID_SIZE, ASK, ASK_SIZE, DAILY_CHANGE, DAILY_CHANGE_RELATIVE, LAST_PRICE, VOLUME, HIGH, LOW],
 * in der Sammelabfrage mit dem Symbol davor. Bitfinex' eigene Kürzel (UST = USDT, UDC = USDC, ATO = ATOM …)
 * werden für die Anzeige übersetzt ([BITFINEX_ALIASES]); die Kennung bleibt die der Börse.
 */
class Bitfinex : SimpleMarket(
    name = "Bitfinex",
    pairListUrl = "$BITFINEX_API/conf/pub:list:pair:exchange",
    tickerUrl = "$BITFINEX_API/ticker/%1\$s",
) {

    // ── Einzelkurs ──

    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val fields = JSONArray(body)
        // Fehlerform: ["error", Code, "Meldung"]
        if (fields.optString(0) == "error") throw MarketParseException(fields.optString(2))
        copyRow(fields, ticker, shift = 0)
    }

    override fun errorMessage(call: Int, body: String, info: PairQuery): String? =
        JSONArray(body).optString(2).ifEmpty { null }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = BITFINEX_TICKERS + "ALL"

    /** Nur die beobachteten Paare («symbols=tBTCUSD,tETHUSD»), aufgeteilt, damit jede URL kurz bleibt. */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        val parts = BulkPairChunks.chunks(BITFINEX_TICKERS, pairIds) ?: return getBulkTickersUrl(call)
        val part = parts.getOrNull(call) ?: return null
        return BulkPairChunks.url(BITFINEX_TICKERS, part)
    }

    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int =
        BulkPairChunks.chunks(BITFINEX_TICKERS, pairIds)?.size ?: bulkTickersNumOfRequests

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONArray { row ->
            val symbol = row.optString(0)
            // «f…» sind Funding-Märkte, keine Handelspaare; leerer Kurs: ruhend, kein Fehler
            if (!symbol.startsWith("t") || BulkTickerRows.inactivePrice(row, 7)) return@forEachJSONArray
            bulkRow(symbol, tickers, failed) { copyRow(row, it, shift = 1) }
        }
    }

    // ── Paarliste ──

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        JSONArray(body).getJSONArray(0).forEachString { raw ->
            if (raw.startsWith("TEST")) return@forEachString
            val (base, quote) = sidesOf(raw) ?: return@forEachString
            out += MarketPair(readable(base), readable(quote), "t$raw")
        }
    }

    // ── Lesen ──

    /** Liest die Ticker-Felder ab Position [shift] (0 = Einzelabruf, 1 = Sammelzeile mit Symbol). */
    private fun copyRow(row: JSONArray, ticker: Ticker, shift: Int) {
        ticker.last = row.getDouble(shift + 6)
        ticker.bid = row.getDouble(shift)
        ticker.ask = row.getDouble(shift + 2)
        ticker.high = row.getDouble(shift + 8)
        ticker.low = row.getDouble(shift + 9)
        ticker.vol = row.getDouble(shift + 7)
        // relative Tagesveränderung = gleitende 24 h als Bruchteil
        ticker.change24hPercent = Change24h.fraction(row.optDouble(shift + 5))
    }

    /** «BTCUSD» oder «DOGE:USD» → (Basis, Kotierung); andere Form → null. */
    private fun sidesOf(raw: String): Pair<String, String>? {
        val colon = raw.indexOf(':')
        return when {
            colon >= 0 -> raw.substring(0, colon) to raw.substring(colon + 1)
            raw.length == 6 -> raw.take(3) to raw.drop(3)
            else -> null
        }
    }

    private fun readable(code: String): String = BITFINEX_ALIASES.getOrElse(code) { code }
}
