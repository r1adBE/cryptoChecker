package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import java.time.LocalDate

/** Umgerechneter Wertverlauf; [approximate] = mit dem heutigen Kurs statt mit Tageskursen. */
data class PortfolioHistoryConverted(val series: PortfolioHistorySeries, val approximate: Boolean)

/**
 * Wertverlauf in einer anderen Währung als USD mit historischen Tageskursen (reines Kotlin,
 * testbar; gespiegelt in `Shared/Portfolio/PortfolioHistoryFx.swift`):
 *  - Jeder Punkt (Wert in USDT, USDT = USD) × Kurs USD → Zielwährung SEINES Tags. Wochenenden und
 *    Feiertage (keine EZB-Fixierung) nehmen den Kurs des letzten Geschäftstags davor ([rateOn]).
 *  - Der heutige Punkt nimmt den aktuellen Kurs (wie die Umrechnungszeile im Kopf).
 *  - Liegt ein Tag vor dem ersten Tageskurs, gilt der erste (die Abfrage beginnt ohnehin
 *    [LEAD_DAYS] Tage früher, damit es einen Geschäftstag davor gibt).
 *  - Ohne Tageskurse (Abfrage fehlgeschlagen): alle Punkte mit dem aktuellen Kurs und
 *    [PortfolioHistoryConverted.approximate] — die Karte zeigt dann «Umgerechnet mit heutigem Kurs».
 *  - Veränderung und Prozent werden aus den umgerechneten Punkten neu gerechnet.
 */
object PortfolioHistoryFx {

    /** So viele Tage vor dem ersten Punkt beginnt die Kursabfrage (deckt lange Feiertage ab). */
    const val LEAD_DAYS = 7

    private fun valid(v: Double?): Double? = v?.takeIf { it > 0.0 && it.isFinite() }

    /** Zeitraum der Kursabfrage für einen Verlauf, der [days] Tage vor [today] beginnt. */
    fun requestRange(days: Int, today: LocalDate): Pair<LocalDate, LocalDate> =
        today.minusDays(days.toLong().coerceAtLeast(0) + LEAD_DAYS) to today

    /**
     * Währungen für die Abfrage: [currency], bei BGN zusätzlich EUR (Lew nach der Euro-Einführung
     * aus EUR × 1.95583, siehe [ratesByDay]) — eine Abfrage für beide.
     */
    fun requestCurrencies(currency: String): List<String> {
        val code = CurrencyConversion.normalize(currency)
        return if (code == "BGN") listOf("BGN", "EUR") else listOf(code)
    }

    /**
     * Tageskurse aus der Antwort der Zeitreihe (Datum «yyyy-MM-dd» → Währung → Kurs) für [currency]:
     * Tag → Kurs. Ungültige Daten und Kurse fallen weg. Fehlt BGN ab dem 1.1.2026, gilt EUR × 1.95583
     * ([CurrencyConversion.deriveBgnFromEur]).
     */
    fun ratesByDay(currency: String, byDate: Map<String, Map<String, Double>>): Map<Long, Double> {
        val code = CurrencyConversion.normalize(currency)
        val out = HashMap<Long, Double>()
        for ((date, rates) in byDate) {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
            val rate = valid(rates[code])
                ?: if (CurrencyConversion.deriveBgnFromEur(code, day)) CurrencyConversion.bgnFromEur(rates["EUR"]) else null
            rate?.let { out[day.toEpochDay()] = it }
        }
        return out
    }

    /** Kurs am Tag [day] oder am letzten Tag davor ([sorted] aufsteigend nach Tag); null davor. */
    fun rateOn(sorted: List<Pair<Long, Double>>, day: Long): Double? {
        var lo = 0
        var hi = sorted.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].first <= day) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0) sorted[found].second else null
    }

    /**
     * Rechnet [series] (USDT) in die Zielwährung um.
     * @param dailyRates Tag (Tage seit 1970-01-01) → Einheiten Zielwährung je USD; null = nicht geladen
     * @param currentRate aktueller Kurs USD → Zielwährung (für heute und als Ausweich)
     * @param todayEpochDay heutiger Tag (lokal)
     */
    fun convert(
        series: PortfolioHistorySeries,
        dailyRates: Map<Long, Double>?,
        currentRate: Double,
        todayEpochDay: Long,
    ): PortfolioHistoryConverted {
        val current = valid(currentRate) ?: 1.0
        val sorted = dailyRates.orEmpty().entries
            .mapNotNull { (day, rate) -> valid(rate)?.let { day to it } }
            .sortedBy { it.first }
        val approximate = sorted.isEmpty()
        if (series.points.isEmpty()) return PortfolioHistoryConverted(series, approximate)
        val points = series.points.map { p ->
            val rate = when {
                p.epochDay >= todayEpochDay || approximate -> current
                else -> rateOn(sorted, p.epochDay) ?: sorted.first().second
            }
            PortfolioHistoryPoint(p.epochDay, p.value * rate)
        }
        val first = points.first().value
        val last = points.last().value
        val change = if (points.size >= 2) last - first else null
        val percent = if (change != null && first > PortfolioCalculator.EPS) change / first * 100.0 else null
        return PortfolioHistoryConverted(
            series.copy(points = points, change = change, changePercent = percent),
            approximate,
        )
    }
}
