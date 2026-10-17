package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.PagedPairList
import com.cryptochecker.marketdata.model.PairListPaging
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.forEachString
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val GEMINI_API = "https://api.gemini.com/v1"

/** Bekannte Quote-Endungen, die längeren zuerst. */
private val GEMINI_QUOTE_ENDINGS =
    listOf("rlusd", "gusd", "usdc", "usdt", "usd", "eur", "gbp", "sgd", "btc", "eth", "sol", "fil")

/**
 * Gemini Spot. `/v1/symbols` nennt nur zusammengeschriebene Symbole («btcusd», «aavegusd»); Basis und
 * Quote trennt eine Liste bekannter Quote-Endungen ([splitSymbols]), Perpetuals («…perp») fallen weg.
 *
 * Passt mehr als eine Endung (z. B. «mogusd» = MOG/USD oder MO/GUSD), wird nicht geraten: solche Symbole
 * holen Basis/Quote aus `/v1/symbols/details/{symbol}` — als Folgeseiten der Paarliste, höchstens
 * [MAX_DETAILS] je Synchronisierung. Die Warteschlange dafür gehört der einzelnen Synchronisierung
 * ([DetailPaging]), nicht der von allen geteilten Börse. Die Antworten merkt sich die Börse ([knownDetails]);
 * was über der Grenze liegt oder eine Fehlerantwort bekam, fehlt vorerst in der Paarliste und kommt bei einer
 * späteren Synchronisierung dran.
 */
class Gemini : SimpleMarket(
    name = "Gemini",
    pairListUrl = "$GEMINI_API/symbols",
    tickerUrl = "$GEMINI_API/pubticker/%1\$s",
    errorField = "message",
), PagedPairList {

    /**
     * Geklärte mehrdeutige Symbole (Symbol → Paar; null = kein Spot-Paar) — Wissen über die Börse, kein
     * Blätterzustand, darum über Synchronisierungen hinweg geteilt.
     */
    private val knownDetails = HashMap<String, MarketPair?>()

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        ticker.last = json.getDouble("last")
        ticker.bid = json.optDoubleOrMissing("bid")
        ticker.ask = json.optDoubleOrMissing("ask")
        // «volume»: {"BTC": "…", "USD": "…", "timestamp": …}; timestamp ist die Momentaufnahme, kein Handel
        val volume = json.optJSONObject("volume") ?: return
        ticker.vol = volume.optDoubleOrMissing(info.base.uppercase())
        ticker.volQuote = volume.optDoubleOrMissing(info.quote.uppercase())
    }

    // ── Sammelabfrage: Preisliste aller Paare, nur der letzte Kurs ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    override val bulkTickersComplete: Boolean
        get() = true

    override fun getBulkTickersUrl(call: Int): String = "$GEMINI_API/pricefeed"

    /**
     * Keine 24-h-Veränderung: Die Einheit von `percentChange24h` ist unklar (Doku Prozent, Antworten eher
     * Bruchteil) — dann lieber Kerzen. Fehlt der Preis oder ist er unlesbar (NaN), meldet [bulkRow] das Paar.
     */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        JSONArray(body).forEachJSONObject { row ->
            val pairId = row.optString("pair").lowercase()
            bulkRowIfActive(pairId, row, "price", tickers, failed) { it.last = row.optDouble("price", Double.NaN) }
        }
    }

    // ── Paarliste ──

    /** Je Synchronisierung eine eigene Warteschlange der Detail-Abfragen. */
    override fun newPairListPaging(): PairListPaging = DetailPaging()

    override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        if (isDetailsPage(body)) {
            readDetailsPage(JSONObject(body), out)
        } else {
            readSymbolList(JSONArray(body), out)
        }
    }

    // ── Lesen ──

    private fun isDetailsPage(body: String): Boolean = body.trimStart().startsWith("{")

    /** Folgeseite mit den Details eines Symbols. Eine Fehlerantwort lässt das Symbol ungeklärt. */
    private fun readDetailsPage(json: JSONObject, out: MutableList<MarketPair>) {
        if (!hasDetails(json)) return
        val pair = detailPair(json)
        // unter dem Symbol der Antwort ablegen — Antworten können versetzt zur Abfrage kommen
        val symbol = json.optString("symbol").trim().lowercase()
        synchronized(knownDetails) { knownDetails[symbol] = pair }
        if (pair != null) out += pair
    }

    private fun readSymbolList(list: JSONArray, out: MutableList<MarketPair>) {
        val known = synchronized(knownDetails) { HashMap(knownDetails) }
        val plan = planSymbols(symbolsOf(list), known.keys)
        for ((symbol, base, quote) in plan.direct) {
            out += MarketPair(base.uppercase(), quote.uppercase(), symbol)
        }
        // früher geklärte mehrdeutige Symbole: Basis/Quote aus deren Details
        for (symbol in plan.cached) known[symbol]?.let { out += it }
    }

    private fun symbolsOf(list: JSONArray): List<String> = buildList { list.forEachString(::add) }

    /**
     * Warteschlange einer Synchronisierung. Die erste Antwort ist die Liste: Sie legt die noch ungeklärten
     * mehrdeutigen Symbole fest ([planSymbols]); jede weitere Antwort — Details oder Fehler — rückt eins weiter.
     */
    private inner class DetailPaging : PairListPaging {
        /** Noch anzufragende Symbole; null, solange die Liste nicht gelesen ist. */
        private var pending: ArrayDeque<String>? = null

        override fun nextPage(call: Int, body: String): String? {
            val queue = pending ?: ArrayDeque(deferredSymbols(body)).also { pending = it }
            return queue.removeFirstOrNull()?.let(::detailsUrl)
        }

        /** Eine unlesbare Liste ist beim Lesen schon gescheitert ([readPairList]) — dann keine Details. */
        private fun deferredSymbols(listBody: String): List<String> {
            val symbols = try {
                symbolsOf(JSONArray(listBody))
            } catch (_: JSONException) {
                return emptyList()
            }
            val resolved = synchronized(knownDetails) { HashSet(knownDetails.keys) }
            return planSymbols(symbols, resolved).deferred.map { it.first }
        }
    }

    companion object {
        /** Höchstens so viele Detail-Abfragen je Synchronisierung (Seitengrenze minus die Liste selbst). */
        const val MAX_DETAILS = Market.MAX_PAIR_LIST_PAGES - 1

        /**
         * Symbole → (Symbol, Basis, Quote), alles klein; Perpetuals und Symbole ohne bekannte Endung fallen weg.
         * Bei mehreren passenden Endungen («mogusd», «btcgusd») gewinnt die Trennung, deren Basis als eindeutiges
         * Symbol in der Liste steht (btcusd → BTC); sonst (keine oder mehrere solche) die kürzere Quote. Nur eine
         * vorläufige Trennung — mehrdeutige Symbole gehen trotzdem immer über die Details ([planSymbols]).
         */
        fun splitSymbols(symbols: List<String>): List<Triple<String, String, String>> = classify(symbols).map { it.split }

        fun detailsUrl(symbol: String): String = "$GEMINI_API/symbols/details/$symbol"

        /**
         * Ergebnis der Planung: Paare direkt aus der Liste ([direct]), mehrdeutige für die Details ([deferred]),
         * früher geklärte ([cached], Symbole) und ungeklärte über der Grenze ([unresolved], Symbole).
         */
        data class Plan(
            val direct: List<Triple<String, String, String>>,
            val deferred: List<Triple<String, String, String>>,
            val cached: List<String> = emptyList(),
            val unresolved: List<String> = emptyList(),
        )

        /**
         * Eindeutige Symbole direkt; mehrdeutige aus [resolved] oder über die Details — höchstens [MAX_DETAILS],
         * der Rest bleibt ungeklärt (nie geraten) bis zu einer späteren Synchronisierung.
         */
        fun planSymbols(symbols: List<String>, resolved: Set<String> = emptySet()): Plan {
            val classified = classify(symbols)
            val direct = ArrayList<Triple<String, String, String>>()
            val cached = ArrayList<String>()
            val waiting = ArrayList<Triple<String, String, String>>()
            for (item in classified) {
                val symbol = item.split.first
                when {
                    !item.ambiguous -> direct += item.split
                    symbol in resolved -> cached += symbol
                    else -> waiting += item.split
                }
            }
            return Plan(
                direct = direct,
                deferred = waiting.take(MAX_DETAILS),
                cached = cached,
                unresolved = waiting.drop(MAX_DETAILS).map { it.first },
            )
        }

        /** Antwort von /v1/symbols/details mit Symbol, Basis und Quote (sonst eine Fehlerantwort). */
        fun hasDetails(json: JSONObject): Boolean =
            listOf("symbol", "base_currency", "quote_currency").all { json.optString(it).isNotBlank() }

        /**
         * Paar aus /v1/symbols/details/{symbol} (`symbol`, `base_currency`, `quote_currency`). Perpetuals und
         * Fehlerantworten → null, es wird nichts geraten.
         */
        fun detailPair(json: JSONObject): MarketPair? {
            if (!hasDetails(json)) return null
            val symbol = json.optString("symbol").trim().lowercase()
            val swap = symbol.endsWith("perp") || json.optString("product_type") == "swap"
            if (swap) return null
            val base = json.optString("base_currency").trim().uppercase()
            val quote = json.optString("quote_currency").trim().uppercase()
            return MarketPair(base, quote, symbol)
        }

        /** Vorläufige Trennung eines Symbols und ob mehr als eine Endung passt (→ Details). */
        private class Classified(val split: Triple<String, String, String>, val ambiguous: Boolean)

        private fun classify(symbols: List<String>): List<Classified> {
            val matches = ArrayList<Pair<String, List<String>>>()
            for (raw in symbols) {
                val symbol = raw.lowercase()
                if (symbol.endsWith("perp")) continue
                val endings = GEMINI_QUOTE_ENDINGS.filter { symbol.length > it.length && symbol.endsWith(it) }
                if (endings.isNotEmpty()) matches += symbol to endings
            }
            val clearBases = HashSet<String>()
            for ((symbol, endings) in matches) {
                if (endings.size == 1) clearBases += symbol.dropLast(endings[0].length)
            }
            return matches.map { (symbol, endings) ->
                val backed = endings.filter { symbol.dropLast(it.length) in clearBases }
                val quote = backed.singleOrNull() ?: endings.last()
                // auch mit genau einer bekannten Basis mehrdeutig: sie beweist die Trennung nicht
                Classified(Triple(symbol, symbol.dropLast(quote.length), quote), endings.size > 1)
            }
        }
    }
}
