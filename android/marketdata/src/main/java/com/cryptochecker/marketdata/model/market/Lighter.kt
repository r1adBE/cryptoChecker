package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachOptJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

private const val LIGHTER_API = "https://mainnet.zklighter.elliot.ai/api/v1"

/** Kennzahlen aller Märkte (letzter Handel, 24-h-Werte), Schlüssel `symbol` — für Einzel- und Sammelkurs. */
private const val LIGHTER_STATS = "$LIGHTER_API/exchangeStats"

/** Gegenwährung aller Perps: Lighter rechnet in USDC ab, das Symbol selbst ist nackt («BTC», «SPY»). */
private const val LIGHTER_PERP_QUOTE = "USDC"

/** `code` einer normalen Antwort. */
private const val LIGHTER_OK = 200

/**
 * Lighter — dezentrale Perpetual-Börse (zk-Rollup) mit USDC-Abrechnung, dazu einige Spot-Märkte; öffentliche
 * Schnittstelle ohne Schlüssel. Paare aus `orderBooks`, Kurse aus `exchangeStats`
 * ({code, total, order_book_stats: [{symbol, last_trade_price, daily_…}]}).
 *
 * Paar-Kennung = `symbol` («BTC» für den Perp, «rhQQQ/USDC» für Spot), nicht `market_id`: Die Sammelabfrage
 * `exchangeStats` nennt nur das Symbol, und die Sammelabfragen werden einzeln gelesen — eine Zuordnung
 * market_id ↔ symbol bräuchte einen geteilten Zustand aus der Paarliste, den der Adapter nicht halten soll.
 * Darum liest auch der Einzelkurs `exchangeStats` (wie Hyperliquid: dieselbe Antwort, daraus das gesuchte Paar);
 * gleichzeitige Einzelkurse teilen sich die Antwort (InFlightRequests). `orderBookDetails?market_id=` hätte
 * zusätzlich Hoch/Tief, bräuchte aber die market_id.
 *
 * Kurs = letzter Handel (`last_trade_price`), kein Mark- oder Mittelkurs. Keine Zeit der Börse (Momentaufnahme),
 * Frische ab Abruf. TradFi-Perps (SPY, NATGAS, US10Y …) bleiben unmarkiert wie bei Hyperliquid: `orderBooks`
 * hat kein Feld, das sie von Krypto unterscheidet, und eine Liste von Hand veraltete bei jeder Neuzulassung.
 */
class Lighter : Market("Lighter", "Lighter") {
    override val key = "Lighter"

    // ── Einzelkurs: dieselbe Antwort wie die Sammelabfrage, daraus das gesuchte Symbol ──

    override fun tickerAddress(call: Int, info: PairQuery): String = LIGHTER_STATS

    /**
     * Unbekanntes Symbol oder Kurs 0 (nie gehandelt, abgelaufener Binärmarkt): Fehler statt Kurs 0, damit
     * «nicht mehr gehandelt» bestätigt werden kann (NotTraded).
     */
    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val symbol = info.pairId.orEmpty()
        var row: JSONObject? = null
        json.getJSONArray("order_book_stats").forEachOptJSONObject { entry ->
            if (row == null && entry.optText("symbol") == symbol) row = entry
        }
        val found = row ?: throw MarketParseException("Unknown symbol: $symbol")
        if (BulkTickerRows.inactivePrice(found, "last_trade_price")) throw MarketParseException("No price: $symbol")
        copyRow(found, ticker)
    }

    /**
     * Fehlerantwort {"code": …, "message": …} mit einem Code ausser 200; eine normale Antwort (code 200) ohne das
     * Paar hat keinen Fehlertext der Börse.
     */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val code = json.optInt("code", LIGHTER_OK)
        val message = json.optText("message")
        if (code == LIGHTER_OK || message.isEmpty()) noErrorText()
        return message
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** `exchangeStats` führt alle Märkte (auch inaktive und Binärmärkte), ein fehlendes Paar ist nicht mehr gelistet. */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = LIGHTER_STATS

    /**
     * Kurs 0 (Binärmärkte «BTC-UPDOWN-15M-…», nie gehandelte Perps) wird übersprungen, nicht als Fehler gezählt;
     * fehlt das Kursfeld (umbenannt), trifft das nur sein Paar.
     */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONObject(body).getJSONArray("order_book_stats").forEachOptJSONObject { row ->
            bulkRowIfActive(row.optText("symbol"), row, "last_trade_price", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun pairListAddress(call: Int): String = "$LIGHTER_API/orderBooks"

    /**
     * Nur aktive, nicht eingefrorene Märkte. Perp «BTC» → BTC/USDC (PERPETUAL), Spot «rhQQQ/USDC» → rhQQQ/USDC;
     * Symbole bleiben, wie Lighter sie schreibt (wie Hyperliquid «kPEPE»). Andere Marktarten werden übergangen.
     */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        json.getJSONArray("order_books").forEachOptJSONObject { book ->
            if (book.optText("status") != "active" || book.optBoolean("is_frozen")) return@forEachOptJSONObject
            readPair(book.optText("symbol"), book.optText("market_type"))?.let { out += it }
        }
    }

    // ── Lesen ──

    /** Eine Zeile von `exchangeStats`; `daily_price_change` ist bereits Prozent über gleitende 24 h. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        ticker.last = row.getDouble("last_trade_price")
        ticker.vol = row.optDoubleOrMissing("daily_base_token_volume")
        ticker.volQuote = row.optDoubleOrMissing("daily_quote_token_volume")
        ticker.change24hPercent = Change24h.percent(row.optDouble("daily_price_change"))
    }

    /** Paar aus Symbol und Marktart; null für leere oder unpassende Symbole und unbekannte Marktarten. */
    private fun readPair(symbol: String, type: String): MarketPair? {
        if (symbol.isEmpty()) return null
        return when (type) {
            "perp" ->
                if ('/' in symbol) null
                else MarketPair(symbol, LIGHTER_PERP_QUOTE, symbol, FuturesContractType.PERPETUAL)
            "spot" -> {
                val slash = symbol.indexOf('/')
                if (slash <= 0 || slash == symbol.length - 1) null
                else MarketPair(symbol.substring(0, slash), symbol.substring(slash + 1), symbol)
            }
            else -> null
        }
    }
}
