package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONObject

private const val MERCADO_API = "https://api.mercadobitcoin.net/api/v4"

/** Werte der Spalte «type», die als Kryptowährung gelten. */
private val MERCADO_CRYPTO_TYPES = setOf("CRYPTO", "UTILITY_TOKEN")

/** Ticker immer mit Auswahl: «?symbols=BTC-BRL,ETH-BRL» (ohne Auswahl gibt es keine Gesamtliste). */
private const val MERCADO_TICKERS = "$MERCADO_API/tickers?symbols="

/**
 * Mercado Bitcoin (Brasilien, BRL-Paare), öffentliche API v4: https://api.mercadobitcoin.net/api/v4/docs —
 * Kennung wie «BTC-BRL». Die Paarliste kommt spaltenweise (je Feld eine Liste, gleiche Stelle = gleiches Paar).
 */
class MercadoBitcoin : SimpleMarket(
    name = "Mercado Bitcoin",
    pairListUrl = "$MERCADO_API/symbols",
    tickerUrl = "$MERCADO_TICKERS%1\$s",
    errorField = "message",
) {
    override val key = "MercadoBitcoin"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}-${info.quote}"

    /** Antwort ist eine Liste; nur die Zeile des angefragten Paars zählt. */
    override fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        val id = pairId(info)
        var found: JSONObject? = null
        JSONArray(body).forEachJSONObject { row -> if (found == null && row.optString("pair") == id) found = row }
        copyRow(found ?: throw MarketParseException(MarketParseException.NO_DATA), ticker)
    }

    // ── Sammelabfrage: nur gefiltert ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Alle beobachteten Paare in einer Anfrage. Lehnt die Börse die Auswahl wegen eines unbekannten Paars ab,
     * lädt die App die Paarliste, lässt Unbekannte weg und fragt neu ([getBulkKnownPairsUrl], wie bei Bithumb).
     * Keine Aufteilung auf mehrere Adressen: Die Wiederholung teilte die Liste neu auf und träfe andere Gruppen.
     */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        if (call != 0 || pairIds.isEmpty()) return null
        return MERCADO_TICKERS + pairIds.sorted().joinToString(",")
    }

    override fun getBulkKnownPairsUrl(call: Int): String? = pairListAddress(call)

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            bulkRowIfActive(row.optString("pair"), row, "last", tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    /**
     * Spalten «symbol», «base-currency», «currency»; gelistet und handelbar laut «exchange-listed/-traded». Nur
     * «type» CRYPTO oder UTILITY_TOKEN: die Börse führt daneben über tausend tokenisierte Forderungen und Anteile
     * (DIGITAL_ASSET, DIGITAL_VARIABLE_INCOME), die keine Kryptowährungen sind (Aufzeichnung 19.10.2026).
     */
    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        val symbols = json.getJSONArray("symbol")
        val bases = json.getJSONArray("base-currency")
        val quotes = json.getJSONArray("currency")
        val listed = json.optJSONArray("exchange-listed")
        val traded = json.optJSONArray("exchange-traded")
        val types = json.optJSONArray("type")
        for (i in 0 until symbols.length()) {
            val id = textAt(symbols, i)
            if (id.isEmpty() || !flagAt(listed, i) || !flagAt(traded, i) || !cryptoAt(types, i)) continue
            out += MarketPair(textAt(bases, i).uppercase(), textAt(quotes, i).uppercase(), id)
        }
    }

    // ── Lesen ──

    /**
     * Eine Ticker-Zeile (Zahlen als Text). `buy`/`sell` = bestes Gebot/Angebot, `vol` in der Basiswährung, `open`
     * = Kurs zu Beginn der letzten 24 h. `date` (s) ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse
     * bleibt unbekannt.
     */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        val last = row.getDouble("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open"))
    }

    /** Text an Stelle [i]; JSON null oder fehlend = leer (das Paar fällt dann aus der Liste). */
    private fun textAt(column: JSONArray, i: Int): String = if (column.isNull(i)) "" else column.optString(i)

    /** Art an Stelle [i] ist eine Kryptowährung; fehlende Spalte gilt als ja, leerer Wert als nein. */
    private fun cryptoAt(column: JSONArray?, i: Int): Boolean =
        column == null || textAt(column, i).uppercase() in MERCADO_CRYPTO_TYPES

    /** Schalter an Stelle [i]; fehlende Spalte oder unklarer Wert gilt als gesetzt. */
    private fun flagAt(column: JSONArray?, i: Int): Boolean = column == null || column.optBoolean(i, true)
}
