// Preis- und Wert-Faktoren der Phemex-Produktliste (Teil von `Phemex.kt`, gespiegelt in `PhemexScales.swift`).
package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.util.forEachJSONObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

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
