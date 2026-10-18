package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.Change24h
import com.cryptochecker.marketdata.util.TradFi
import com.cryptochecker.marketdata.util.forEachJSONObject
import com.cryptochecker.marketdata.util.optDoubleOrMissing
import com.cryptochecker.marketdata.util.optText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/** Produktliste (Paare und Faktoren); bei fehlenden Faktoren auch erste Anfrage der Spot-Kurse. */
private const val PHEMEX_PRODUCTS = "https://api.phemex.com/public/products"

private const val PHEMEX_MD = "https://api.phemex.com/md"

/**
 * Phemex Spot. Symbole beginnen mit «s» (sBTCUSDT). Die Börse liefert ganze Zahlen (Ep/Ev), die erst mit
 * [PhemexScales] zu Kursen werden: Preise (Ep) durch 10^`priceScale` des Produkts (ohne Angabe kein Kurs),
 * Menge (volumeEv, Basis) und Umsatz (turnoverEv, Kotierung) durch 10^`valueScale` der jeweiligen Währung aus
 * `/public/products` (meist 8; SHIB 2, VPAD 4). Doku: github.com/phemex/phemex-api-docs.
 * [PhemexFutures] bekommt Dezimaltexte (Rp) und braucht keine Faktoren.
 */
class Phemex : PhemexBase(
    name = "Phemex",
    tickerUrl = "$PHEMEX_MD/spot/ticker/24hr?symbol=%1\$s",
    contractType = FuturesContractType.NONE,
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String =
        info.pairId ?: "s${info.base}${info.quote}"

    /**
     * Fehlen die Faktoren, ist Anfrage 0 die Produktliste (diesmal ohne Kurs, ab der nächsten Abfrage mit — sonst
     * hinge ein Paar ohne Sammelabfrage für immer im Fehler). Veraltete Faktoren ([PhemexScales.isStale]) frischt
     * eine zweite Anfrage nach dem Kurs auf.
     */
    override fun tickerCallCount(info: PairQuery?): Int = callsFor(PhemexScales.current)

    override fun tickerAddress(call: Int, info: PairQuery): String {
        val products = when (call) {
            0 -> !PhemexScales.current.loaded
            1 -> true
            else -> false
        }
        return if (products) PHEMEX_PRODUCTS else super.tickerAddress(call, info)
    }

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val productData = productDataOf(json)
        if (productData == null) {
            super.fillTickerJson(call, json, ticker, info)
            return
        }
        // Antwort war die Produktliste: Faktoren übernehmen (und ablegen); als Anfrage 0 gibt es noch keinen Kurs
        PhemexScales.current = PhemexScales.parse(productData)
        if (call == 0) throw MarketParseException(PhemexScales.JUST_LOADED)
    }

    // ── Sammelabfrage ──

    /**
     * Ohne Faktoren (auch keine gespeicherten): erst `/public/products`, dann die Kurse. Veraltete Faktoren: die
     * Produktliste als zweite Anfrage nach den Kursen (scheitert sie, gelten die alten weiter — Gruppe 1).
     */
    override val bulkTickersNumOfRequests: Int
        get() = callsFor(PhemexScales.current)

    override fun getBulkTickersUrl(call: Int): String =
        if (isProductsCall(call)) PHEMEX_PRODUCTS else "$PHEMEX_MD/spot/ticker/24hr/all"

    /** Die Auffrischung (Anfrage 1) ist eine eigene Gruppe: Scheitert sie, bleiben die Kurse vollständig. */
    override fun bulkTickersRequestGroup(call: Int): Int =
        if (call == 1 && isProductsCall(1)) 1 else 0

    /** Spot ohne Faktoren: Diese Antwort ist die Produktliste — Faktoren übernehmen, keine Kurse. */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val productData = productDataOf(JSONObject(body))
        if (productData != null) {
            PhemexScales.current = PhemexScales.parse(productData)
            return
        }
        super.parseBulkTickers(call, body, tickers, failed)
    }

    // ── Lesen ──

    override fun copyRow(row: JSONObject, ticker: Ticker, symbol: String, base: String?, quote: String?) {
        // Ohne Faktoren aus der Produktliste (oder ihrer Kopie) kein Kurs: geraten läge er Zehnerpotenzen daneben
        val scales = PhemexScales.current
        if (!scales.loaded) throw MarketParseException(PhemexScales.NOT_LOADED)
        // Symbol ohne priceScale in der Liste (fehlt oder neu): ebenfalls kein Kurs statt geratener 10^8
        val priceDivisor = scales.priceDivisor(symbol)
            ?: throw MarketParseException(PhemexScales.UNKNOWN_SCALE.format(symbol))
        val (baseCode, quoteCode) = scales.currencies(symbol, base, quote)

        val last = row.getLong("lastEp") / priceDivisor
        ticker.last = last
        ticker.bid = row.scaled("bidEp", priceDivisor)
        ticker.ask = row.scaled("askEp", priceDivisor)
        ticker.high = row.scaled("highEp", priceDivisor)
        ticker.low = row.scaled("lowEp", priceDivisor)
        ticker.vol = row.scaled("volumeEv", scales.valueDivisor(baseCode))
        ticker.volQuote = row.scaled("turnoverEv", scales.valueDivisor(quoteCode))
        // «timestamp» (ns) = Momentaufnahme (≈ jetzt), kein Handel: Zeit der Börse bleibt offen.
        // «openEp» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(last, row.scaled("openEp", priceDivisor))
    }

    /** `data` der Produktliste, falls [json] diese ist (statt eines Tickers); sonst null. */
    private fun productDataOf(json: JSONObject): JSONObject? = json.optJSONObject("data")?.takeIf { it.has("products") }

    private fun isProductsCall(call: Int): Boolean {
        val scales = PhemexScales.current
        return if (!scales.loaded) call == 0 else call == 1 && scales.isStale()
    }

    private fun callsFor(scales: PhemexScales): Int = if (scales.isStale()) 2 else 1

    /** Ganzzahlfeld [name] durch [divisor]; fehlt es oder ist es null: keine Daten. */
    private fun JSONObject.scaled(name: String, divisor: Double): Double =
        if (!has(name) || isNull(name)) Ticker.MISSING else optLong(name) / divisor
}

/** Phemex USDT-Perpetuals: Dezimaltexte (Rp/Rq/Rv), ohne Geld-/Briefkurs. */
class PhemexFutures : PhemexBase(
    name = "Phemex Futures",
    tickerUrl = "$PHEMEX_MD/v2/ticker/24hr?symbol=%1\$s",
    contractType = FuturesContractType.PERPETUAL,
) {

    // ── Einzelkurs ──

    override fun pairId(info: PairQuery): String = info.pairId ?: "${info.base}${info.quote}"

    // ── Sammelabfrage ──

    override fun getBulkTickersUrl(call: Int): String = "$PHEMEX_MD/v2/ticker/24hr/all"

    // ── Lesen ──

    override fun copyRow(row: JSONObject, ticker: Ticker, symbol: String, base: String?, quote: String?) {
        val close = row.getDouble("closeRp")
        ticker.last = close
        ticker.high = row.optDoubleOrMissing("highRp")
        ticker.low = row.optDoubleOrMissing("lowRp")
        ticker.vol = row.optDoubleOrMissing("volumeRq")
        ticker.volQuote = row.optDoubleOrMissing("turnoverRv")
        // «timestamp» (ns): Momentaufnahme wie bei Spot. «openRp» = Kurs vor 24 h
        ticker.change24hPercent = Change24h.fromOpen(close, row.optDouble("openRp"))
    }
}

/** Gemeinsames von Spot und Perpetuals: Paarliste aus `/public/products`, Fehlerformat, Sammelabfrage. */
abstract class PhemexBase(
    name: String,
    tickerUrl: String,
    private val contractType: FuturesContractType,
) : SimpleMarket(name = name, pairListUrl = PHEMEX_PRODUCTS, tickerUrl = tickerUrl, ttsName = "Phemex") {

    private val isSpot: Boolean
        get() = contractType == FuturesContractType.NONE

    /** Liest einen Ticker; [symbol] = Paar-Kennung, [base]/[quote] wenn bekannt (Einzelabfrage). */
    protected abstract fun copyRow(row: JSONObject, ticker: Ticker, symbol: String, base: String?, quote: String?)

    // ── Einzelkurs ──

    override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
        val symbol = pairId(info).orEmpty()
        copyRow(json.getJSONObject("result"), ticker, symbol, info.base, info.quote)
    }

    /** Fehlerformen: {"error":{…}} oder {"code":…,"msg":"…"}. */
    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val nested = json.optJSONObject("error")
        if (nested != null) return nested.optText("message").ifEmpty { nested.toString() }
        return json.optText("msg").ifEmpty { noErrorText() }
    }

    // ── Sammelabfrage ──

    override val bulkTickersNumOfRequests: Int
        get() = 1

    /** Eine Fehlerantwort statt Liste scheitert mit dem Text der Börse, statt still leer zu bleiben (wie iOS). */
    override fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) {
        val root = JSONObject(body)
        val rows = root.optJSONArray("result") ?: throw MarketParseException(bulkFailureText(root))
        val priceField = if (isSpot) "lastEp" else "closeRp"
        rows.forEachJSONObject { row ->
            val symbol = row.optString("symbol")
            bulkRowIfActive(symbol, row, priceField, tickers, failed) { copyRow(row, it, symbol, null, null) }
        }
    }

    // ── Paarliste ──

    override fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) {
        val data = json.getJSONObject("data")
        if (isSpot) collectSpot(data, out) else collectPerpetuals(data, out)
    }

    // ── Lesen ──

    private fun collectSpot(data: JSONObject, out: MutableList<MarketPair>) {
        // Mit der Paarliste kommen die Faktoren (Ep/Ev): gleich übernehmen
        PhemexScales.current = PhemexScales.parse(data)
        data.getJSONArray("products").forEachJSONObject { product ->
            if (product.optString("type") != "Spot" || product.optString("status") != "Listed") return@forEachJSONObject
            out += MarketPair(
                product.getString("baseCurrency"),
                product.getString("quoteCurrency"),
                product.getString("symbol"),
            )
        }
    }

    private fun collectPerpetuals(data: JSONObject, out: MutableList<MarketPair>) {
        val list = data.optJSONArray("perpProductsV2") ?: return
        list.forEachJSONObject { product ->
            if (product.optString("status") != "Listed") return@forEachJSONObject
            val kind = product.optString("type")
            if (kind.isNotEmpty() && !kind.contains("Perpetual", ignoreCase = true)) return@forEachJSONObject
            out += MarketPair(
                product.getString("baseCurrency"),
                product.getString("quoteCurrency"),
                product.getString("symbol"),
                contractType,
                // perpProductSubType «TradFi» (Aktien, Rohstoffe …) wie bei Binance/Bybit markieren
                TradFi.phemex(product.optText("perpProductSubType")),
            )
        }
    }

    private fun bulkFailureText(root: JSONObject): String {
        val nested = root.optJSONObject("error")?.optText("message")
        if (!nested.isNullOrEmpty()) return nested
        return root.optText("msg").ifEmpty { "No result" }
    }
}

/**
 * Faktoren der Phemex-Spot-Produkte aus `/public/products`, geladen mit der Paarliste und im Speicher wie als Datei
 * gehalten ([store]):
 *  - [priceScale] je Symbol (Ep; ohne Angabe kein Kurs — nie geraten),
 *  - [valueScale] je Währung (Ev, Liste `currencies`),
 *  - [pairs] Symbol → (Basis, Kotierung).
 * Ohne Produktliste ([loaded] false) zeigt Phemex keinen Kurs; die Sammelabfrage holt sie dann zuerst
 * ([Phemex.bulkTickersNumOfRequests]) — ein Faktor wird nie geraten. [DOCUMENTED_VALUE_SCALES] greifen nur für
 * Währungen ohne Eintrag in der Liste. Reines Kotlin; Gegenstück in `Phemex.swift`.
 */
class PhemexScales(
    val priceScale: Map<String, Int> = emptyMap(),
    val valueScale: Map<String, Int> = emptyMap(),
    val pairs: Map<String, Pair<String, String>> = emptyMap(),
    /** Stammt aus der Produktliste oder ihrer gespeicherten Kopie; false = nie geladen. */
    val loaded: Boolean = false,
    /** Zeitpunkt der Produktliste in ms; 0 = unbekannt (alte Datei), gilt als veraltet. */
    val loadedAt: Long = 0L,
) {
    /** Neu laden, wenn nie geladen oder das Alter nicht in [0, [REFRESH_MILLIS]) liegt. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        if (!loaded) return true
        val age = now - loadedAt
        return age < 0 || age >= REFRESH_MILLIS
    }

    /**
     * Teiler der Preise (Ep) von [symbol]; null, wenn die Produktliste für das Symbol keinen `priceScale` nennt
     * (fehlt in der Liste, neu gelistet). Dann zeigt das Paar einen Fehler: 8 zu raten läge bei anderen Faktoren
     * Zehnerpotenzen daneben.
     */
    fun priceDivisor(symbol: String): Double? = priceScale[symbol]?.let { tenTo(it) }

    /** Teiler der Werte (Ev) in [currency]: Liste, dann dokumentierte Ausnahme, sonst 8. */
    fun valueDivisor(currency: String?): Double {
        val code = currency?.uppercase() ?: return tenTo(DEFAULT_SCALE)
        val scale = valueScale[code] ?: DOCUMENTED_VALUE_SCALES[code] ?: DEFAULT_SCALE
        return tenTo(scale)
    }

    /** Basis und Kotierung: aus der Produktliste, sonst [base]/[quote], sonst am Symbolende erraten. */
    fun currencies(symbol: String, base: String?, quote: String?): Pair<String?, String?> {
        val listed = pairs[symbol]
        if (listed != null) return listed
        if (!base.isNullOrEmpty() && !quote.isNullOrEmpty()) return base to quote
        return guessFromSymbol(symbol)
    }

    /** Dateiformat für [store]: `{"v":1,"t":…,"price":{…},"value":{…},"pairs":{"sBTCUSDT":["BTC","USDT"]}}`. */
    fun encode(): String {
        val pairObject = JSONObject()
        for ((symbol, pair) in pairs) pairObject.put(symbol, JSONArray().put(pair.first).put(pair.second))
        val root = JSONObject()
        root.put("v", FORMAT_VERSION)
        root.put("t", loadedAt)
        root.put("price", JSONObject(priceScale as Map<*, *>))
        root.put("value", JSONObject(valueScale as Map<*, *>))
        root.put("pairs", pairObject)
        return root.toString()
    }

    /**
     * Wo die App die Faktoren ablegt ([store]). Die Ablage ist nur ein Vorrat für den nächsten Start: Fehler darin
     * bleiben still, denn ohne Kopie lädt die nächste Abfrage einfach die Produktliste neu.
     */
    interface Store {
        fun load(): String?
        fun save(text: String)
    }

    /** Ablage in einer Datei — geschrieben über eine Hilfsdatei, die dann umbenannt wird. */
    class FileStore(private val file: File) : Store {
        /** Unlesbare Datei = keine Kopie (siehe [Store]). */
        override fun load(): String? =
            try {
                if (file.isFile) file.readText() else null
            } catch (_: Exception) {
                null
            }

        /** Scheitert das Schreiben, fehlt beim nächsten Start nur die Kopie (siehe [Store]). */
        override fun save(text: String) {
            try {
                val staging = File(file.parentFile, "${file.name}.tmp")
                staging.writeText(text)
                if (staging.renameTo(file)) return
                // Manche Dateisysteme ersetzen beim Umbenennen nicht: alte Datei weg, dann nochmals
                file.delete()
                staging.renameTo(file)
            } catch (_: Exception) {
                // bewusst still, siehe KDoc
            }
        }
    }

    companion object {
        const val DEFAULT_SCALE = 8
        const val FORMAT_VERSION = 1

        /** Fehlertext, solange die Produktliste fehlt (lieber kein Kurs als ein falscher). */
        const val NOT_LOADED = "Phemex: product list not loaded yet"

        /** Fehlertext für ein Symbol ohne `priceScale` in der Produktliste (%s = Symbol). */
        const val UNKNOWN_SCALE = "Phemex: no price scale for %s in the product list"

        /** Fehlertext der Einzelabfrage, deren Anfrage 0 eben die fehlende Produktliste geholt hat. */
        const val JUST_LOADED = "Phemex: product list loaded, price follows with the next update"

        /** Einmal am Tag neu laden. */
        const val REFRESH_MILLIS = 24L * 60 * 60 * 1000

        /** Public-Spot-API, «All Spot trading currencies»: Wertfaktoren ungleich 8. */
        val DOCUMENTED_VALUE_SCALES: Map<String, Int> = mapOf("SHIB" to 2, "VPAD" to 4)

        /** Kotierungen, an denen ein Symbol ohne Produktliste geteilt wird (Reihenfolge zählt). */
        private val QUOTE_SUFFIXES = listOf("USDT", "USDC", "BTC", "ETH", "TRY", "BRZ")

        /** Ablage auf dem Gerät; null = nur Speicher (Tests). Neu gesetzt: erneuter Ladeversuch erlaubt. */
        @Volatile
        var store: Store? = null
            set(value) {
                synchronized(lock) {
                    field = value
                    diskChecked = false
                }
            }

        /** Sperre für [held] und [diskChecked] ([current]). */
        private val lock = Any()

        @Volatile
        private var held: PhemexScales = PhemexScales()

        /** Gespeicherte Kopie für die aktuelle Ablage schon versucht. */
        @Volatile
        private var diskChecked = false

        /**
         * Die zuletzt gelesenen Faktoren (geteilt, als Ganzes ersetzt). Noch keine geladen: einmalig die Kopie aus
         * [store] versuchen. Neue Faktoren aus der Produktliste werden sofort abgelegt.
         */
        var current: PhemexScales
            // Unter einer Sperre (wie iOS): Sonst überschriebe ein gleichzeitiges erstes Lesen von der Ablage
            // die eben aus der Produktliste gesetzten, neueren Faktoren
            get() {
                synchronized(lock) {
                    val now = held
                    if (now.loaded || diskChecked) return now
                    diskChecked = true
                    val fromDisk = store?.load()?.let { decode(it) } ?: return now
                    held = fromDisk
                    return fromDisk
                }
            }
            set(next) {
                synchronized(lock) {
                    held = next
                    if (next.loaded) store?.save(next.encode())
                }
            }

        /** Gegenstück zu [encode]; andere Version, unlesbar oder ganz ohne Angaben → null (dann neu laden). */
        fun decode(text: String?): PhemexScales? {
            if (text.isNullOrBlank()) return null
            return try {
                val root = JSONObject(text)
                if (root.optInt("v") != FORMAT_VERSION) {
                    null
                } else {
                    val price = scaleMap(root.optJSONObject("price"))
                    val value = scaleMap(root.optJSONObject("value"))
                    val pairs = pairMap(root.optJSONObject("pairs"))
                    if (price.isEmpty() && value.isEmpty() && pairs.isEmpty()) null
                    else PhemexScales(price, value, pairs, true, root.optLong("t", 0L))
                }
            } catch (_: Exception) {
                // Kaputte Kopie: wie keine Kopie behandeln, die Produktliste wird neu geladen
                null
            }
        }

        /** Faktoren aus `data` von `/public/products`; fehlende Angaben bleiben weg (dann Standard). */
        fun parse(data: JSONObject, now: Long = System.currentTimeMillis()): PhemexScales {
            val value = HashMap<String, Int>()
            data.optJSONArray("currencies")?.forEachJSONObject { entry ->
                val code = entry.optString("currency").uppercase()
                if (code.isNotEmpty() && entry.hasValue("valueScale")) {
                    value[code] = entry.optInt("valueScale", DEFAULT_SCALE)
                }
            }
            val price = HashMap<String, Int>()
            val pairs = HashMap<String, Pair<String, String>>()
            data.optJSONArray("products")?.forEachJSONObject { product ->
                if (product.optString("type") != "Spot") return@forEachJSONObject
                val symbol = product.optString("symbol")
                if (symbol.isEmpty()) return@forEachJSONObject
                if (product.hasValue("priceScale")) price[symbol] = product.optInt("priceScale", DEFAULT_SCALE)
                val base = product.optString("baseCurrency")
                val quote = product.optString("quoteCurrency")
                if (base.isNotEmpty() && quote.isNotEmpty()) pairs[symbol] = base.uppercase() to quote.uppercase()
            }
            return PhemexScales(price, value, pairs, true, now)
        }

        private fun JSONObject.hasValue(name: String): Boolean = has(name) && !isNull(name)

        private fun scaleMap(source: JSONObject?): Map<String, Int> {
            if (source == null) return emptyMap()
            val result = HashMap<String, Int>()
            for (key in source.keys()) {
                if (!source.isNull(key)) result[key] = source.optInt(key, DEFAULT_SCALE)
            }
            return result
        }

        private fun pairMap(source: JSONObject?): Map<String, Pair<String, String>> {
            val result = HashMap<String, Pair<String, String>>()
            if (source == null) return result
            for (key in source.keys()) {
                val legs = source.optJSONArray(key) ?: continue
                val base = legs.optString(0)
                val quote = legs.optString(1)
                if (base.isNotEmpty() && quote.isNotEmpty()) result[key] = base to quote
            }
            return result
        }

        /** «sSHIBUSDT» → SHIB/USDT; keine bekannte Kotierung am Ende → (null, null). */
        private fun guessFromSymbol(symbol: String): Pair<String?, String?> {
            val core = symbol.removePrefix("s")
            val quote = QUOTE_SUFFIXES.firstOrNull { core.length > it.length && core.endsWith(it) }
                ?: return null to null
            return core.dropLast(quote.length) to quote
        }

        private fun tenTo(scale: Int): Double = 10.0.pow(scale.coerceIn(0, 18))
    }
}
