package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.ReferenceSource
import com.cryptochecker.marketdata.model.Ticker
import org.json.JSONArray
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Referenzkurse der Zentralbanken neben der EZB ([EcbRates]), frei und ohne Schlüssel. iOS: `ReferenceRates.swift`,
 * gemeinsame Fälle in `testdata/parity/reference_rates.json`.
 *  - Nationalbank der Ukraine (NBU, bank.gov.ua): täglich, Kurse in UAH. Eine Abfrage über [NBU_WINDOW_DAYS] Tage
 *    ([nbuRangeUrl]); jeder Kalendertag hat einen Eintrag, eine Veröffentlichung erkennt man an `calcdate`.
 *  - Polnische Nationalbank (NBP, api.nbp.pl): Tabelle B wöchentlich am Mittwoch, Kurse in PLN; USD und EUR derselben
 *    Veröffentlichung aus der täglichen Tabelle A desselben `effectiveDate`.
 *
 * Eine Veröffentlichung = Code → Kurs in der Heimwährung der Quelle (Heimwährung selbst = 1). Jede Währung X, die die
 * Quelle liefert ([assign]), gibt es gegen USD und EUR: Kreuzkurs r(X) / r(Gegenwährung) derselben Veröffentlichung,
 * auf 5 signifikante Stellen ([EcbRates.round5], dieselbe Regel wie die EZB-Kreuzkurse). Veränderung gegen die vorige
 * Veröffentlichung derselben Quelle (NBU: voriger Tag, NBP: vorige Woche).
 */
object ReferenceRates {
    /** Aktuelle Kurse der NBU (nur für die Paarliste: welche Währungen es gibt). */
    const val NBU_LIST_URL = "https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json"
    private const val NBU_RANGE_URL = "https://bank.gov.ua/NBU_Exchange/exchange_site"
    private const val NBP_API = "https://api.nbp.pl/api/exchangerates/tables"

    /** Jüngste Tabellen A (täglich) — deckt die Mittwoche der zwei jüngsten Tabellen B auch über Feiertage ab. */
    const val NBP_TABLE_A_URL = "$NBP_API/A/last/15?format=json"

    /** Die zwei jüngsten Tabellen B (wöchentlich): Kurs und Veränderung gegen die Vorwoche. */
    const val NBP_TABLE_B_URL = "$NBP_API/B/last/2?format=json"

    /** Jüngste Tabelle A bzw. B (nur für die Paarliste). */
    const val NBP_LIST_A_URL = "$NBP_API/A?format=json"
    const val NBP_LIST_B_URL = "$NBP_API/B?format=json"

    /** Tage der NBU-Abfrage vor heute: genug für die vorige Veröffentlichung auch über lange Wochenenden. */
    const val NBU_WINDOW_DAYS = 7L

    /** Neu gelesen wird spätestens nach so viel Zeit, wenn die erwartete Veröffentlichung noch fehlt (1 h). */
    const val RETRY_MILLIS = 60L * 60 * 1000

    /** Gegenwährungen der Paare von NBU und NBP. */
    val QUOTES = listOf("USD", "EUR")

    /**
     * Keine Währung im Sinn der App: Edelmetalle und Sonderziehungsrecht (XAU, XAG, XPT, XPD, XDR) sowie zurückgezogene
     * ISO-4217-Codes, falls eine Quelle sie noch führt.
     */
    val EXCLUDED: Set<String> = setOf(
        "XAU", "XAG", "XPT", "XPD", "XDR",
        "ANG", "AZM", "BGN", "BYR", "CSD", "CUC", "CYP", "EEK", "GHC", "HRK", "LTL", "LVL", "MGF", "MRO", "MTL", "MZM",
        "ROL", "SDD", "SIT", "SKK", "SLL", "SRG", "STD", "TMM", "TRL", "VEB", "VEF", "XEU", "XFU", "YUM", "ZMK", "ZWD",
        "ZWL", "ZWR",
    )

    /** Zeitzonen der Veröffentlichung («Europe/Kiev» kennt auch jede ältere Zeitzonen-Datenbank). */
    val KYIV: ZoneId = ZoneId.of("Europe/Kiev")
    val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Uhrzeit (Warschau), ab der die Tabelle B des Mittwochs erscheint. */
    private val NBP_PUBLISHED_AT: LocalTime = LocalTime.of(12, 15)

    private val CODE = Regex("[A-Z]{3}")

    fun isCode(code: String): Boolean = CODE.matches(code)

    // ── Quelle je Währung ──

    /**
     * Jede Währung aus genau einer Quelle: EZB (samt EUR) vor NBU (samt UAH) vor NBP (samt PLN); [EXCLUDED] und
     * ungültige Codes fallen weg. Code → Quelle.
     */
    fun assign(ecb: Collection<String>, nbu: Collection<String>, nbp: Collection<String>): Map<String, ReferenceSource> {
        val out = LinkedHashMap<String, ReferenceSource>()
        fun take(codes: Collection<String>, source: ReferenceSource) {
            for (code in (codes + source.home).sorted()) {
                if (isCode(code) && code !in EXCLUDED && code !in out) out[code] = source
            }
        }
        take(ecb, ReferenceSource.ECB)
        take(nbu, ReferenceSource.NBU)
        take(nbp, ReferenceSource.NBP)
        return out
    }

    /** Paare der Quelle [source] laut [assigned]: jede Währung gegen USD und EUR, Kennung «AEDUSD». */
    fun pairs(assigned: Map<String, ReferenceSource>, source: ReferenceSource): List<ExchangePair> =
        assigned.filterValues { it == source }.keys.sorted().flatMap { code ->
            QUOTES.filter { it != code }.map { quote -> ExchangePair(code, quote, code + quote) }
        }

    // ── Kurse ──

    /** Einheiten [quote] je 1 [base] aus einer Veröffentlichung [day] (5 Stellen); null ohne beide Kurse oder für [EXCLUDED]. */
    fun cross(day: Map<String, Double>, base: String, quote: String): Double? {
        if (base == quote || base in EXCLUDED || quote in EXCLUDED) return null
        val baseRate = day[base]?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        val quoteRate = day[quote]?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        return EcbRates.round5(baseRate / quoteRate)
    }

    /**
     * Kurs aus der jüngsten Veröffentlichung von [publications] (aufsteigend), Veränderung gegen die vorige. false, wenn
     * die jüngste keinen Kurs hat — dann nie der Kurs einer älteren als «aktuell».
     */
    fun copy(publications: List<Map<String, Double>>, base: String, quote: String, ticker: Ticker): Boolean {
        val last = publications.lastOrNull()?.let { cross(it, base, quote) } ?: return false
        ticker.last = last
        ticker.change24hPercent = publications.getOrNull(publications.size - 2)
            ?.let { cross(it, base, quote) }?.let { Change24h.fromOpen(last, it) }
        return true
    }

    // ── NBU ──

    /** Ein Tag der NBU: Datum «yyyy-MM-dd», Tag der Festlegung ([calc], gleich = dieselbe Veröffentlichung), Kurse in UAH. */
    data class NbuDay(val date: String, val calc: String, val rates: Map<String, Double>)

    /** Abfrage der Tage [NBU_WINDOW_DAYS] vor [today] bis [today] (Kiewer Datum), alle Währungen. */
    fun nbuRangeUrl(today: LocalDate): String =
        "$NBU_RANGE_URL?start=${compact(today.minusDays(NBU_WINDOW_DAYS))}&end=${compact(today)}" +
            "&sort=exchangedate&order=desc&json"

    /** Codes der aktuellen NBU-Liste ([NBU_LIST_URL]); null, wenn unlesbar. */
    fun nbuCodes(body: String): Set<String>? = runCatching {
        val rows = JSONArray(body)
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("cc")?.takeIf(::isCode) }.toSet()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Tage der Abfrage [nbuRangeUrl], aufsteigend: `exchangedate` «dd.MM.yyyy», `cc`, `rate_per_unit` (sonst
     * `rate` / `units`), `calcdate`. Kurse ≤ 0 und ungültige Codes fallen weg. null, wenn unlesbar oder leer.
     */
    fun parseNbuRange(body: String): List<NbuDay>? = runCatching {
        val rows = JSONArray(body)
        val rates = HashMap<String, HashMap<String, Double>>()
        val calcs = HashMap<String, String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val date = isoOf(row.optString("exchangedate")) ?: continue
            val code = row.optString("cc").takeIf(::isCode) ?: continue
            val perUnit = row.optDouble("rate_per_unit").takeIf { it.isFinite() }
                ?: (row.optDouble("rate") / row.optDouble("units", 1.0))
            if (!(perUnit > 0.0 && perUnit.isFinite())) continue
            rates.getOrPut(date) { HashMap() }[code] = perUnit
            isoOf(row.optString("calcdate"))?.let { calcs.putIfAbsent(date, it) }
        }
        rates.keys.sorted().map { NbuDay(it, calcs[it] ?: it, rates.getValue(it)) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Jüngste Veröffentlichung der NBU und die davor (anderes `calcdate`; Wochenend-Tage wiederholen die vom Freitag),
     * aufsteigend, je mit UAH = 1.
     */
    fun nbuPublications(days: List<NbuDay>): List<Map<String, Double>> {
        val newest = days.maxByOrNull { it.date } ?: return emptyList()
        val previous = days.filter { it.date < newest.date && it.calc != newest.calc }.maxByOrNull { it.date }
        return listOfNotNull(previous, newest).map { it.rates + ("UAH" to 1.0) }
    }

    /** NBU-Antwort gültig bis Mitternacht (Kiew), wenn sie den heutigen Tag enthält; sonst [RETRY_MILLIS]. */
    fun nbuValidUntil(days: List<NbuDay>?, now: Long): Long {
        val newest = days?.maxOfOrNull { it.date } ?: return 0L
        val today = dateIn(now, KYIV)
        if (newest < today.toString()) return now + RETRY_MILLIS
        return today.plusDays(1).atStartOfDay(KYIV).toInstant().toEpochMilli()
    }

    // ── NBP ──

    /** Tabellen der NBP (A oder B): `effectiveDate` → Code → Kurs in PLN (`mid`); null, wenn unlesbar oder leer. */
    fun parseNbp(body: String): Map<String, Map<String, Double>>? = runCatching {
        val tables = JSONArray(body)
        val out = HashMap<String, Map<String, Double>>()
        for (i in 0 until tables.length()) {
            val table = tables.optJSONObject(i) ?: continue
            val date = table.optString("effectiveDate").takeIf { DATE.matches(it) } ?: continue
            val rates = table.optJSONArray("rates") ?: continue
            val day = HashMap<String, Double>()
            for (j in 0 until rates.length()) {
                val rate = rates.optJSONObject(j) ?: continue
                val code = rate.optString("code").takeIf(::isCode) ?: continue
                val mid = rate.optDouble("mid")
                if (mid > 0.0 && mid.isFinite()) day[code] = mid
            }
            if (day.isNotEmpty()) out[date] = day
        }
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Die zwei jüngsten Tabellen B, aufsteigend, je ergänzt um Tabelle A desselben `effectiveDate` (USD, EUR und
     * die übrigen Währungen der Tabelle A) und PLN = 1. Fehlt Tabelle A dieses Tags, fehlen USD und EUR — dann gibt
     * es für diese Woche keinen Kurs, nie einen Kreuzkurs über verschiedene Tage.
     */
    fun nbpPublications(tableA: Map<String, Map<String, Double>>?, tableB: Map<String, Map<String, Double>>?): List<Map<String, Double>> {
        val b = tableB ?: return emptyList()
        return b.keys.sorted().takeLast(2).map { date -> tableA?.get(date).orEmpty() + b.getValue(date) + ("PLN" to 1.0) }
    }

    /**
     * Antworten der NBP gültig bis zur nächsten Tabelle B (Mittwoch 12:15 Warschau), wenn die jüngste Tabelle B die
     * erwartete ist (frühestens zwei Tage vor dem Mittwoch: vorgezogen wegen Feiertag) und Tabelle A ihren Tag hat;
     * sonst [RETRY_MILLIS].
     */
    fun nbpValidUntil(tableA: Map<String, Map<String, Double>>?, tableB: Map<String, Map<String, Double>>?, now: Long): Long {
        val newest = tableB?.keys?.maxOrNull() ?: return 0L
        if (tableA == null) return 0L
        val expected = nbpExpectedWednesday(now)
        if (newest < expected.minusDays(2).toString() || newest !in tableA) return now + RETRY_MILLIS
        return expected.plusDays(7).atTime(NBP_PUBLISHED_AT).atZone(WARSAW).toInstant().toEpochMilli()
    }

    /** Mittwoch der jüngsten Tabelle B, die zum Zeitpunkt [now] schon erschienen sein sollte. */
    fun nbpExpectedWednesday(now: Long): LocalDate {
        val today = dateIn(now, WARSAW)
        val back = (today.dayOfWeek.value - DayOfWeek.WEDNESDAY.value + 7) % 7
        val wednesday = today.minusDays(back.toLong())
        val publishedAt = wednesday.atTime(NBP_PUBLISHED_AT).atZone(WARSAW).toInstant().toEpochMilli()
        return if (now < publishedAt) wednesday.minusDays(7) else wednesday
    }

    // ── Hilfen ──

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val DOTTED = Regex("(\\d{2})\\.(\\d{2})\\.(\\d{4})")

    /** Kalendertag von [now] (ms) in [zone]. */
    fun dateIn(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    /** «05.11.2026» → «2026-11-05»; sonst null. */
    private fun isoOf(dotted: String): String? =
        DOTTED.matchEntire(dotted.trim())?.destructured?.let { (d, m, y) -> "$y-$m-$d" }

    /** 2026-11-05 → «20261105». */
    private fun compact(day: LocalDate): String = day.toString().replace("-", "")
}
