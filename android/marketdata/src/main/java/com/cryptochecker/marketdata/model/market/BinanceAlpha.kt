package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Binance Alpha — frühe Token ausserhalb des regulären Spot-Angebots. Öffentliche Endpunkte ohne Schlüssel
 * auf www.binance.com (developers.binance.com/docs/alpha, «Market Data»; Stand Oktober 2026).
 *
 * In der App: Paare «SYMBOL/USDT» unter der Börse «Binance», Kennung «ALPHA_<id>USDT» ([pairId], gleich dem
 * `symbol` der Alpha-Endpunkte). Kurse kommen aus der Token-Liste ([readListRow]) oder — bei wenigen Paaren —
 * je Paar vom Ticker ([bulkRequestUrls], [readTicker]); Kerzen von den Klines ([klinesUrl]). Wie `BinanceAlpha.swift`.
 */
object BinanceAlpha {
    /** Alle Token samt Kurs, 24-h-Veränderung und -Umsatz. */
    const val TOKEN_LIST_URL =
        "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"

    /** 24-h-Ticker; die Kennung wird angehängt («…?symbol=ALPHA_175USDT»). */
    const val TICKER_URL = "https://www.binance.com/bapi/defi/v1/public/alpha-trade/ticker?symbol="

    /** Kerzen (Parameter `symbol`, `interval`, `limit`). */
    const val KLINES_URL = "https://www.binance.com/bapi/defi/v1/public/alpha-trade/klines"

    /** Börse der Alpha-Paare ([com.cryptochecker.marketdata.model.Market.key]). */
    const val MARKET_KEY = "Binance"

    /** Quelle der Kerzen («Chart: Binance Alpha»). */
    const val PROVIDER = "Binance Alpha"

    /** Gegenwährung aller Alpha-Paare. */
    const val QUOTE = "USDT"

    /**
     * Grenze für Einzel-Ticker: bis hierhin je Paar ein Ticker (rund 0,4 KB), darüber einmal die ganze
     * Token-Liste (rund 1 KB pro Token) — siehe [bulkRequestUrls].
     */
    const val MAX_SINGLE_TICKERS = 10

    /** Gültigkeit der geteilten Token-Liste ([listCache]) für Merkliste, Widgets und Alarme. */
    const val LIST_CACHE_MILLIS = 60_000L

    /** `code` einer erfolgreichen Alpha-Antwort. */
    private const val SUCCESS = "000000"

    private val idPattern = Regex("ALPHA_[0-9]{1,9}")
    private val pairPattern = Regex("ALPHA_[0-9]{1,9}[A-Z]{2,10}")

    /**
     * Ein Token der Alpha-Liste: Kürzel, voller Name (für «gleicher Token») und `alphaId` («ALPHA_175»;
     * null = unbekannt, etwa aus der Ersatzdatei — daraus wird kein Paar).
     */
    data class Token(val symbol: String, val name: String?, val alphaId: String?)

    /** Hat [id] die Form eines Alpha-Paars («ALPHA_175USDT»)? */
    fun isPairId(id: String?): Boolean = id?.let { pairPattern.matches(it) } ?: false

    /** Alpha-Paar der Börse Binance ([MARKET_KEY])? */
    fun isAlphaPair(marketKey: String, pairId: String?): Boolean =
        marketKey == MARKET_KEY && isPairId(pairId)

    /** `alphaId` bereinigt und gross («ALPHA_175»); ungültig → null. */
    fun alphaId(raw: String?): String? {
        val candidate = raw?.trim()?.uppercase() ?: return null
        return if (idPattern.matches(candidate)) candidate else null
    }

    /** Paar-Kennung zu [alphaId] («ALPHA_175» → «ALPHA_175USDT»); ungültig → null. */
    fun pairId(alphaId: String?, quote: String = QUOTE): String? = alphaId(alphaId)?.plus(quote)

    /** Kürzel wie in der Paarliste: Leer- und unsichtbare Formatzeichen entfernt, gross. */
    fun cleanSymbol(raw: String?): String {
        val kept = StringBuilder()
        for (ch in raw.orEmpty()) {
            if (ch.isWhitespace() || Character.getType(ch) == Character.FORMAT.toInt()) continue
            kept.append(ch)
        }
        return kept.toString().uppercase()
    }

    /**
     * Paare «SYMBOL/USDT» aus [tokens] (bereits gefiltert: handelbar, nicht regulär gelistet; Reihenfolge =
     * Vorrang), höchstens eins je `alphaId`. Spot-Paare ([spotPairs]) bleiben unberührt. Ist ein Kürzel schon
     * belegt (Spot oder früheres Alpha-Token), handelt es sich um einen anderen Token gleichen Kürzels: er
     * bleibt, heisst aber «SYMBOL_<Nummer>» ([distinctBase]) — eindeutig für Paarliste, Logos, Namen und
     * Ausweich-Kerzen. Die Kennung bleibt «ALPHA_<id>USDT».
     */
    fun pairs(tokens: List<Token>, spotPairs: List<MarketPair>): List<MarketPair> {
        val usedBases = spotPairs
            .filter { !isPairId(it.pairId) }
            .mapTo(HashSet()) { it.base.uppercase() }
        val seenIds = HashSet<String>()
        val result = ArrayList<MarketPair>()
        for (token in tokens) {
            val id = pairId(token.alphaId) ?: continue
            val symbol = cleanSymbol(token.symbol)
            if (symbol.isEmpty()) continue
            if (!seenIds.add(id)) continue
            val base = if (usedBases.add(symbol)) {
                symbol
            } else {
                distinctBase(symbol, id).also { usedBases.add(it) }
            }
            result.add(MarketPair(base, QUOTE, id))
        }
        return result
    }

    /** Ersatzkürzel bei Doppelbelegung: «PEPE» mit «ALPHA_500USDT» → «PEPE_500». */
    fun distinctBase(symbol: String, pairId: String): String {
        val digits = pairId.removePrefix("ALPHA_").takeWhile { it in '0'..'9' }
        return "${symbol}_$digits"
    }

    /**
     * Alpha-Teil der Binance-Sammelabfrage für [pairIds]. Erster Eintrag: die Token-Liste (nur bei mehr als
     * [MAX_SINGLE_TICKERS] Alpha-Paaren, sonst null als Platzhalter), danach je Alpha-Paar ein Ticker —
     * sortiert und ohne Doppelte. Ohne Alpha-Paare: leere Liste.
     */
    fun bulkRequestUrls(pairIds: Collection<String>): List<String?> {
        val alpha = pairIds.filter(::isPairId).toSortedSet().toList()
        if (alpha.isEmpty()) return emptyList()
        if (alpha.size > MAX_SINGLE_TICKERS) return listOf(TOKEN_LIST_URL)
        val urls = ArrayList<String?>(alpha.size + 1)
        urls.add(null)
        alpha.mapTo(urls) { TICKER_URL + it }
        return urls
    }

    /**
     * Hält den zuletzt geladenen Text der Token-Liste höchstens [maxAgeMillis] lang im Speicher, damit eine
     * Aktualisierung sie nicht doppelt lädt.
     */
    class ListCache(private val maxAgeMillis: Long = LIST_CACHE_MILLIS) {
        private var text: String? = null
        private var storedAt = 0L

        /** Gespeicherter Text, solange jünger als [maxAgeMillis]; sonst null. */
        @Synchronized
        fun get(now: Long): String? {
            val age = now - storedAt
            return if (now >= storedAt && age < maxAgeMillis) text else null
        }

        @Synchronized
        fun put(text: String, now: Long) {
            this.text = text
            storedAt = now
        }
    }

    /** Geteilter Speicher der Token-Liste für alle Kursabfragen. */
    val listCache = ListCache()

    /**
     * Ticker-Antwort in der Sammelabfrage: Kennung (`data.symbol`) zurück, Kurs in [ticker]. Anderer Code oder
     * kein `data` (Paar unbekannt, z. B. abgesetzt) → null, das Paar fehlt dann wie ein Token ohne Kurs.
     * Fehlen Kennung oder Kurs: Fehler.
     */
    @Throws(JSONException::class)
    fun readBulkTicker(json: String, ticker: Ticker): String? {
        val envelope = JSONObject(json)
        val code = envelope.optText("code")
        val failedCode = code.isNotEmpty() && code != SUCCESS
        if (failedCode || envelope.isNull("data")) return null
        val payload = envelope.get("data") as? JSONObject
            ?: throw JSONException("Alpha ticker without object")
        val symbol = payload.optText("symbol").trim().uppercase()
        if (!isPairId(symbol)) throw JSONException("Alpha ticker without symbol")
        readTicker(json, ticker)
        return symbol
    }

    /** Inhalt `data` einer Alpha-Antwort; Fehler-Code oder fehlendes `data` werfen. */
    @Throws(JSONException::class)
    private fun payloadOf(json: String): Any {
        val envelope = JSONObject(json)
        val code = envelope.optText("code")
        if (code.isNotEmpty() && code != SUCCESS) throw JSONException("Alpha code $code")
        if (envelope.isNull("data")) throw JSONException("Alpha without data")
        return envelope.get("data")
    }

    /** Zeilen der Token-Liste (Array `data`). */
    @Throws(JSONException::class)
    fun listRows(json: String): JSONArray =
        payloadOf(json) as? JSONArray ?: throw JSONException("Alpha list without array")

    /** Paar-Kennung einer Listenzeile (`alphaId` + USDT); null = keine. */
    fun listRowPairId(row: JSONObject): String? = pairId(row.optText("alphaId"))

    /**
     * Kurs einer Listenzeile in [ticker]; false = nicht gehandelt (`price` ≤ 0). Verwendet `price`,
     * `percentChange24h` (Prozent), `volume24h` (USD), `priceHigh24h`/`priceLow24h` (0 = unbekannt). Die Liste
     * kennt keine Handelszeit. Fehlt `price` (Format geändert), wird geworfen.
     */
    @Throws(JSONException::class)
    fun readListRow(row: JSONObject, ticker: Ticker): Boolean {
        val price = row.getDouble("price")
        if (!(price.isFinite() && price > 0.0)) return false
        ticker.last = price
        above(row, "priceHigh24h", 0.0, inclusive = false)?.let { ticker.high = it }
        above(row, "priceLow24h", 0.0, inclusive = false)?.let { ticker.low = it }
        above(row, "volume24h", 0.0, inclusive = true)?.let { ticker.volQuote = it }
        ticker.change24hPercent = Change24h.percent(row.optDouble("percentChange24h"))
        return true
    }

    /**
     * Ticker-Antwort ([TICKER_URL]) in [ticker]: `data` mit `lastPrice`, `priceChangePercent` (Prozent),
     * `highPrice`, `lowPrice`, `volume`, `quoteVolume`. `closeTime` ist nur das Fensterende — die Zeit der
     * Börse bleibt unbekannt (wie Spot).
     */
    @Throws(JSONException::class)
    fun readTicker(json: String, ticker: Ticker) {
        val data = payloadOf(json) as? JSONObject ?: throw JSONException("Alpha ticker without object")
        val last = data.getDouble("lastPrice")
        if (!(last.isFinite() && last > 0.0)) throw JSONException("Alpha ticker without price")
        ticker.last = last
        above(data, "highPrice", 0.0, inclusive = false)?.let { ticker.high = it }
        above(data, "lowPrice", 0.0, inclusive = false)?.let { ticker.low = it }
        above(data, "volume", 0.0, inclusive = true)?.let { ticker.vol = it }
        above(data, "quoteVolume", 0.0, inclusive = true)?.let { ticker.volQuote = it }
        ticker.change24hPercent = Change24h.percent(data.optDouble("priceChangePercent"))
    }

    /** Fehlertext einer Alpha-Antwort: `message`, ersatzweise `messageDetail`; null = keiner. */
    fun errorText(json: JSONObject): String? {
        val text = json.optText("message").ifEmpty { json.optText("messageDetail") }
        return if (text.isBlank()) null else text
    }

    /** Kerzen-Adresse; [interval] wie bei Binance («1h», «4h», «1d»), [limit] auf 1…1000 begrenzt. */
    fun klinesUrl(pairId: String, interval: String, limit: Int): String {
        val count = limit.coerceIn(1, 1000)
        return "$KLINES_URL?symbol=$pairId&interval=$interval&limit=$count"
    }

    /** Kerzen-Array einer Klines-Antwort (Zeilen wie Binance: [openTime, open, high, low, close, volume, closeTime, quoteVolume, …]). */
    @Throws(JSONException::class)
    fun klines(json: String): JSONArray =
        payloadOf(json) as? JSONArray ?: throw JSONException("Alpha klines without array")

    /** Endliche Zahl über [floor] (bei [inclusive] auch gleich), sonst null. */
    private fun above(source: JSONObject, field: String, floor: Double, inclusive: Boolean): Double? {
        val value = source.optDouble(field)
        if (!value.isFinite()) return null
        val ok = if (inclusive) value >= floor else value > floor
        return if (ok) value else null
    }
}
