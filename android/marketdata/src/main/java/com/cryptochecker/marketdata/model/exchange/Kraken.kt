package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.generic.SimpleExchange
import com.cryptochecker.marketdata.util.BulkPairChunks
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.forEachName
import com.cryptochecker.marketdata.util.optText
import org.json.JSONObject

/** Öffentliche REST-Schnittstelle von Kraken (docs.kraken.com, «Market Data»). */
private const val KRAKEN_PUBLIC = "https://api.kraken.com/0/public"

/** Ticker ohne Filter: alle handelbaren Paare. */
private const val KRAKEN_TICKER = "$KRAKEN_PUBLIC/Ticker"

/** Ticker mit Paarliste («?pair=A,B,C»). */
private const val KRAKEN_TICKER_FOR = "$KRAKEN_TICKER?pair="

/** AssetPairs-`status` eines handelbaren Paars; andere Werte (z. B. «cancel_only») stehen nicht zur Wahl. */
private const val KRAKEN_STATUS_ONLINE = "online"

/** Grund, wenn `result` leer ist und Kraken keinen eigenen Fehler nennt. */
private const val KRAKEN_EMPTY_RESULT = "Empty result"

/** Kraken Spot: Paare aus AssetPairs, Kurse aus Ticker (Einzelpaar, Auswahl oder alle). */
class Kraken : SimpleExchange(
    name = "Kraken",
    pairListUrl = "$KRAKEN_PUBLIC/AssetPairs",
    tickerUrl = "$KRAKEN_TICKER_FOR%1\$s",
) {
    override val key = "Kraken"

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: (KrakenAssetCodes.krakenCode(info.base) + KrakenAssetCodes.krakenCode(info.quote))

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val result = json.getJSONObject("result")
        // Eine Anfrage = ein Paar: Der einzige Schlüssel ist dessen Kennung; ohne Schlüssel kein Kurs
        val onlyKey = result.names()?.getString(0) ?: throw ExchangeParseException(KRAKEN_EMPTY_RESULT)
        val row = result.getJSONObject(onlyKey)
        // Schlusskurs 0 (z. B. «cancel_only»): kein Kurs statt 0 — sonst bliebe der alte Kurs still stehen.
        // Ohne Kurs bestätigt die Einzelabfrage «nicht mehr gehandelt» (NotTraded.confirmedBySingle).
        if (isDormant(row)) return
        copyRow(row, ticker)
    }

    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? =
        json.getJSONArray("error").getString(0)

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /**
     * Gefiltert kommen alle angefragten Paare (sonst lehnt Kraken ab), ungefiltert alle handelbaren. Fehlt eines,
     * prüft die Einzelabfrage («EQuery:Unknown asset pair» → nicht mehr gehandelt, BulkDelisted).
     */
    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = KRAKEN_TICKER

    /** Beobachtete Paare gefiltert, bei langer Liste auf mehrere URLs (< 2000 Zeichen) verteilt. */
    override fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? {
        val groups = BulkPairChunks.chunks(KRAKEN_TICKER_FOR, pairIds) ?: return KRAKEN_TICKER
        val group = groups.getOrNull(call) ?: return null
        return BulkPairChunks.url(KRAKEN_TICKER_FOR, group)
    }

    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int =
        BulkPairChunks.chunks(KRAKEN_TICKER_FOR, pairIds)?.size ?: bulkTickersNumOfRequests

    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val root = JSONObject(body)
        val result = root.optJSONObject("result")
        if (result == null || result.length() == 0) {
            // Ein unbekanntes Paar lässt die ganze gefilterte Anfrage scheitern — dann kommt die ungefilterte
            val reason = root.optJSONArray("error")?.optString(0).orEmpty()
            throw ExchangeParseException(reason.ifEmpty { KRAKEN_EMPTY_RESULT })
        }
        result.forEachName { id, row ->
            // Leerer Schlusskurs («c» null oder [0/""/null, …]): ruhender Markt, kein Lesefehler
            if (isDormant(row)) return@forEachName
            bulkRow(id, tickers, failed) { copyRow(row, it) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<ExchangePair>) {
        json.getJSONObject("result").forEachName { id, asset ->
            // Kennungen mit Punkt (z. B. «.d») sind Varianten, keine eigenen Märkte
            if ('.' in id) return@forEachName
            // Nur handelbare Paare («online»); ältere Antworten ohne `status` gelten als handelbar
            val status = asset.optText("status")
            if (status.isNotEmpty() && status != KRAKEN_STATUS_ONLINE) return@forEachName
            // Basis/Gegenwährung aus `wsname` («XBT/USD»), nicht aus `base`/`quote` mit alten Präfixen (KrakenAssetCodes)
            val (base, quote) = KrakenAssetCodes.fromWsName(asset.optString("wsname")) ?: return@forEachName
            out += ExchangePair(base, quote, id)
        }
    }

    // ── Lesen ──

    /** Einzel- und Sammelabfrage: je Paar dieselben Listenfelder. */
    private fun copyRow(row: JSONObject, ticker: Ticker) {
        // c/b/a: [Kurs, …]
        ticker.last = row.slot("c", 0)
        ticker.bid = row.slot("b", 0)
        ticker.ask = row.slot("a", 0)
        // h/l/v: [seit 00:00 UTC, letzte 24 h] — angezeigt werden 24-h-Werte
        ticker.high = row.slot("h", 1)
        ticker.low = row.slot("l", 1)
        ticker.vol = row.slot("v", 1)
        // «o» ist die Tageseröffnung (UTC), nicht der Kurs vor 24 h — darum keine 24-h-Veränderung
    }

    /** Der Schlusskurs «c» ist leer — als Feld oder als erster Listeneintrag (Einzel- und Sammelabfrage). */
    private fun isDormant(row: JSONObject): Boolean {
        if (BulkTickerRows.inactivePrice(row, "c")) return true
        val close = row.optJSONArray("c") ?: return false
        return BulkTickerRows.inactivePrice(close, 0)
    }

    /** Zahl an Position [index] der Liste [key]; Liste fehlt oder zu kurz: «keine Daten» statt 0. */
    private fun JSONObject.slot(key: String, index: Int): Double {
        val values = optJSONArray(key)
        return if (values != null && index < values.length()) values.getDouble(index) else Ticker.MISSING
    }
}
