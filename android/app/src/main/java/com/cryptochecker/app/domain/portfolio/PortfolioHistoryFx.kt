package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import java.time.LocalDate

/**
 * Umgerechneter Wertverlauf; [approximate] = mit dem heutigen Kurs statt mit Tageskursen. [includesFx] = mit Tageskursen
 * umgerechnet: Änderung und Gewinn/Verlust enthalten die Bewegung des Devisenkurses — «7 T»/«1 J» im Kopf rechnen dann
 * mit denselben Tageskursen ([PortfolioPeriodChange.gain] `fx`, [rateFunction]); die reine USDT-Zahl steht separat.
 * Kopf der Karte dann «inkl. Devisenkurs» ([PortfolioHistoryFx.includesFxMovement]).
 */
data class PortfolioHistoryConverted(
    val series: PortfolioHistorySeries,
    val approximate: Boolean,
    val includesFx: Boolean = !approximate,
)

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
 *  - Gewinn/Verlust: Werte und Zuflüsse je mit dem Kurs ihres Tags (ein Kauf zählt zum Kurs des Kauftags).
 */
object PortfolioHistoryFx {

    /** So viele Tage vor dem ersten Punkt beginnt die Kursabfrage (deckt lange Feiertage ab). */
    const val LEAD_DAYS = 7

    private fun valid(v: Double?): Double? = v?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * Anzeigewährung ≠ USD/USDT: Beträge in USDT werden mit dem Kurs USD → [currency] umgerechnet (USDT gilt als 1 USD).
     * Für den Hinweis «USDT zum USD-Kurs umgerechnet»; false für USD/USDT (keine Umrechnung).
     */
    fun convertsUsdtAsUsd(currency: String): Boolean = CurrencyConversion.normalize(currency).let { it != "USD" && it != "USDT" }

    /**
     * Wertverlauf in [currency] mit Tageskursen enthält die Bewegung des Devisenkurses (Punkte und Zuflüsse je zum Kurs
     * ihres Tags), ebenso «7 T»/«1 J» im Kopf, sobald dieselben Tageskurse geladen sind ([rateFunction]) — Kopf der
     * Karte: «inkl. Devisenkurs». false für USD/USDT oder mit dem heutigen Kurs ([approximate]).
     */
    fun includesFxMovement(currency: String, approximate: Boolean): Boolean = convertsUsdtAsUsd(currency) && !approximate

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
     * Kurs USD → Zielwährung je Tag, wie [convert] ihn nutzt: ab [todayEpochDay] (und ohne Tageskurse) der aktuelle
     * [currentRate], sonst der Kurs des Tags bzw. des letzten Geschäftstags davor ([rateOn]), vor dem ersten der erste.
     * Auch für «7 T»/«1 J» in der Anzeigewährung ([PortfolioPeriodChange.gain] `fx`) — damit Kopf und Kurve dieselbe
     * Zahl zeigen.
     */
    fun rateFunction(dailyRates: Map<Long, Double>?, currentRate: Double, todayEpochDay: Long): (Long) -> Double {
        val current = valid(currentRate) ?: 1.0
        val sorted = dailyRates.orEmpty().entries
            .mapNotNull { (day, rate) -> valid(rate)?.let { day to it } }
            .sortedBy { it.first }
        return { day ->
            when {
                day >= todayEpochDay || sorted.isEmpty() -> current
                else -> rateOn(sorted, day) ?: sorted.first().second
            }
        }
    }

    /**
     * «7 T»/«1 J» in der Anzeigewährung ([change], aus [PortfolioPeriodChange.gain] mit `fx`) als Gegenwert zum
     * heutigen Kurs [currentRate] für die Gesamtkarte, die Beträge mit dem heutigen Kurs anzeigt (Betrag ÷ Kurs ×
     * Kurs = Betrag der Kurve). Prozent unverändert (schon auf die umgerechnete Basis).
     */
    fun forCard(change: TodayChange, currentRate: Double): TodayChange {
        val rate = valid(currentRate) ?: return change
        return change.copy(amountUsd = change.amountUsd / rate, rewardsUsd = change.rewardsUsd / rate)
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
        val approximate = dailyRates.orEmpty().values.none { valid(it) != null }
        if (series.points.isEmpty()) return PortfolioHistoryConverted(series, approximate)
        val rateFor = rateFunction(dailyRates, currentRate, todayEpochDay)
        fun convertPoint(p: PortfolioHistoryPoint): PortfolioHistoryPoint {
            val rate = rateFor(p.epochDay)
            // Eröffnung/Hoch/Tief mit demselben Tageskurs wie der Wert
            return PortfolioHistoryPoint(p.epochDay, p.value * rate, p.open?.times(rate), p.high?.times(rate), p.low?.times(rate))
        }
        val points = series.points.map(::convertPoint)
        // Zuflüsse mit dem Kurs ihres Tags (wie die Punkte) — Gewinn/Verlust in der Währung samt Währungseffekt
        val flows = series.cashFlows?.map { it.copy(amount = it.amount * rateFor(it.epochDay)) }
        val pnlValues = series.pnlValues.map(::convertPoint)
        val pnlFlows = series.pnlFlows.map { it.copy(amount = it.amount * rateFor(it.epochDay)) }
        val first = points.first().value
        val last = points.last().value
        val change = if (points.size >= 2) last - first else null
        val percent = if (change != null && first > PortfolioCalculator.EPS) change / first * 100.0 else null
        return PortfolioHistoryConverted(
            series.copy(
                points = points, change = change, changePercent = percent, cashFlows = flows,
                pnlValues = pnlValues, pnlFlows = pnlFlows,
            ),
            approximate,
        )
    }
}
