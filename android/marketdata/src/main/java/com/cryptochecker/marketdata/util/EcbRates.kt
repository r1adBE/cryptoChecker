package com.cryptochecker.marketdata.util

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.time.LocalDate
import java.util.TreeMap
import javax.xml.parsers.SAXParserFactory
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Euro-Referenzkurse direkt von der Europäischen Zentralbank (frei, ohne Schlüssel, ohne Drittanbieter). Alle Werte
 * sind Einheiten je 1 EUR, Tage «yyyy-MM-dd» (nur EZB-Arbeitstage). iOS: `EcbRates.swift`.
 *  - [DAILY_URL]: der jüngste Tag, alle Währungen (Umrechnungszeile, Paarliste der Forex-Paare).
 *  - [HIST_90D_URL]: die letzten 90 Kalendertage, alle Währungen (Forex-Paare: Kurs und Veränderung zum vorigen
 *    EZB-Tag; Ersatz für historische Kurse dieser Zeit, wenn das Data Portal nicht antwortet).
 *  - [seriesUrl]: ECB Data Portal (SDMX, JSON) für beliebige Tage und Zeiträume — eine Abfrage für alle angefragten
 *    Währungen, nur diese Währungen und der Zeitraum gehen hinaus.
 *  - [HIST_URL]: alle Tage seit 1999 (gepackt rund 1 MB, ungepackt rund 8 MB): Ersatz für ältere Zeiträume, wenn das
 *    Data Portal nicht antwortet (dort scheitern erstmals gestellte Abfragen immer wieder mit HTTP 504 nach 30 s).
 * Kreuzkurse ([cross]) mit derselben Genauigkeit wie die bisher bezogenen: Basis EUR = Wert der EZB unverändert, jede
 * andere Basis = Quotient der beiden Euro-Kurse, auf [SIGNIFICANT_DIGITS] signifikante Stellen gerundet ([round5]).
 */
object EcbRates {
    const val DAILY_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
    const val HIST_90D_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"
    const val HIST_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.xml"
    private const val DATA_API = "https://data-api.ecb.europa.eu/service/data/EXR/D."

    /** Bis so viele Tage vor heute deckt [HIST_90D_URL] sicher ab (90 Kalendertage, Reserve für Zeitzone/Feiertage). */
    const val HIST_90D_SAFE_DAYS = 80L

    /** Signifikante Stellen eines Kreuzkurses («%.5g», Genauigkeit der bisher bezogenen Kreuzkurse). */
    const val SIGNIFICANT_DIGITS = 5

    private val CODE = Regex("[A-Z]{3}")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Tageskurse der Währungen [codes] (ohne EUR, sortiert) von [from] bis [to] als SDMX-JSON. Wie iOS `seriesURL`. */
    fun seriesUrl(codes: Collection<String>, from: LocalDate, to: LocalDate): String =
        DATA_API + codes.filter { it != "EUR" }.distinct().sorted().joinToString("+") +
            ".EUR.SP00.A?startPeriod=$from&endPeriod=$to&format=jsondata&detail=dataonly"

    /** Liegt [from] noch sicher in [HIST_90D_URL] (bezogen auf [today], UTC)? */
    fun coveredByHist90d(from: LocalDate, today: LocalDate): Boolean = !from.isBefore(today.minusDays(HIST_90D_SAFE_DAYS))

    /**
     * Tage aus `eurofxref-daily.xml`, `eurofxref-hist-90d.xml` oder `eurofxref-hist.xml` (`<Cube time="…"><Cube
     * currency="USD" rate="…"/>`): Tag → Code → Kurs, aufsteigend; mit [only] nur diese Codes (die ganze Geschichte
     * bleibt so klein). Kurse ≤ 0 oder ungültige Codes fallen weg. null ohne lesbaren Tag oder mit DOCTYPE/ENTITY
     * (externe Entitäten werden nie aufgelöst).
     */
    fun parseXml(body: String, only: Set<String>? = null): Map<String, Map<String, Double>>? {
        if ("<!DOCTYPE" in body || "<!ENTITY" in body) return null
        val days = TreeMap<String, MutableMap<String, Double>>()
        val handler = object : DefaultHandler() {
            private var day: MutableMap<String, Double>? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName
                if (name != "Cube") return
                val time = attributes.getValue("time")
                if (time != null) {
                    day = if (DATE.matches(time)) days.getOrPut(time) { HashMap() } else null
                    return
                }
                val code = attributes.getValue("currency") ?: return
                val rate = attributes.getValue("rate")?.toDoubleOrNull() ?: return
                if (only != null && code !in only) return
                if (CODE.matches(code) && rate > 0.0 && rate.isFinite()) day?.put(code, rate)
            }
        }
        val parsed = runCatching {
            SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(body)), handler)
        }
        return days.filterValues { it.isNotEmpty() }.takeIf { parsed.isSuccess && it.isNotEmpty() }
    }

    /**
     * Antwort des Data Portals (`format=jsondata&detail=dataonly`): Tag → Code → Kurs. Leerer Text = gültige Antwort
     * ohne Kurse im Zeitraum (leere Karte). null, wenn unlesbar (z. B. HTML-Fehlerseite).
     */
    fun parseSdmx(body: String): Map<String, Map<String, Double>>? {
        if (body.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(body)
            val dimensions = root.getJSONObject("structure").getJSONObject("dimensions")
            val seriesDims = dimensions.getJSONArray("series")
            val position = (0 until seriesDims.length()).first { seriesDims.getJSONObject(it).optString("id") == "CURRENCY" }
            val codes = seriesDims.getJSONObject(position).getJSONArray("values")
            val times = dimensions.getJSONArray("observation").getJSONObject(0).getJSONArray("values")
            val out = TreeMap<String, MutableMap<String, Double>>()
            val series = root.getJSONArray("dataSets").getJSONObject(0).optJSONObject("series") ?: JSONObject()
            for (key in series.keys()) {
                val code = key.split(':').getOrNull(position)?.toIntOrNull()?.let { idOf(codes, it) }
                    ?.takeIf(CODE::matches) ?: continue
                val observations = series.getJSONObject(key).optJSONObject("observations") ?: continue
                for (index in observations.keys()) {
                    val date = index.toIntOrNull()?.let { idOf(times, it) }?.takeIf(DATE::matches) ?: continue
                    val rate = observations.optJSONArray(index)?.optDouble(0) ?: continue
                    if (rate > 0.0 && rate.isFinite()) out.getOrPut(date) { HashMap() }[code] = rate
                }
            }
            out
        }.getOrNull()
    }

    private fun idOf(values: JSONArray, index: Int): String? = values.optJSONObject(index)?.optString("id")

    /**
     * Kurs [base] → [quote] aus den Euro-Kursen eines Tags [day]: Einheiten [quote] je 1 [base]. Basis EUR = Wert der EZB,
     * sonst Quotient auf [SIGNIFICANT_DIGITS] Stellen ([round5]). null, wenn eine Seite fehlt oder beide gleich sind.
     */
    fun cross(day: Map<String, Double>, base: String, quote: String): Double? {
        if (base == quote) return null
        val baseRate = eurRate(day, base) ?: return null
        val quoteRate = eurRate(day, quote) ?: return null
        return if (base == "EUR") quoteRate else round5(quoteRate / baseRate)
    }

    /** Alle Kurse eines Tags zur Basis [base] (EUR eingeschlossen, [base] selbst nicht); leer ohne Kurs von [base]. */
    fun rebase(day: Map<String, Double>, base: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (code in day.keys + "EUR") cross(day, base, code)?.let { out[code] = it }
        return out
    }

    /** Alle Codes eines Tags samt EUR, sortiert. */
    fun codes(day: Map<String, Double>): List<String> = (day.keys.filter(CODE::matches) + "EUR").distinct().sorted()

    /** Die Tage aus [days] innerhalb von [windowDays] Tagen vor dem jüngsten Tag, aufsteigend. Wie iOS `recent`. */
    fun recent(days: Map<String, Map<String, Double>>, windowDays: Long): List<Map<String, Double>> {
        val newest = days.keys.maxOrNull() ?: return emptyList()
        val start = runCatching { LocalDate.parse(newest).minusDays(windowDays).toString() }.getOrNull() ?: return emptyList()
        return days.entries.filter { it.key >= start }.sortedBy { it.key }.map { it.value }
    }

    private fun eurRate(day: Map<String, Double>, code: String): Double? =
        if (code == "EUR") 1.0 else day[code]?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * [value] auf [SIGNIFICANT_DIGITS] signifikante Stellen, halbe Stelle aufwärts (wie «%.5g», bis auf
     * exakte Halbwerte). Nur mit Zehnerpotenzen gerechnet, die exakt darstellbar sind — gleiches Ergebnis auf iOS.
     */
    fun round5(value: Double): Double {
        if (!value.isFinite() || value <= 0.0) return value
        val shift = SIGNIFICANT_DIGITS - 1 - floor(log10(value)).toInt()
        return if (shift >= 0) {
            val scale = 10.0.pow(shift)
            floor(value * scale + 0.5) / scale
        } else {
            val scale = 10.0.pow(-shift)
            floor(value / scale + 0.5) * scale
        }
    }
}
