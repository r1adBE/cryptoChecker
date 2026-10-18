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
    /** Mit USDT/USD-Marktkursen ([UsdtUsdRates]) umgerechnet, aber an mindestens einem Tag ohne Kurs: dort USDT = 1. */
    val usdtApproximate: Boolean = false,
)

/**
 * Wertverlauf in einer anderen Währung als USD mit historischen Tageskursen (reines Kotlin,
 * testbar; gespiegelt in `Shared/Portfolio/PortfolioHistoryFx.swift`):
 *  - Jeder Punkt (Wert in USDT) × Kurs USD → Zielwährung SEINES Tags (× USDT/USD-Tagesschluss, wenn [UsdtUsdRates]
 *    mitgegeben: USDT zum Marktkurs, Tag ohne Schluss = 1 als Näherung, [PortfolioHistoryConverted.usdtApproximate];
 *    ohne sie USDT = USD wie bis Build 58). Wochenenden und
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
     * Werden USDT-Beträge für [currency] umgerechnet? Mit USDT zum Marktkurs ([usdtMarket]) auch für USD (USDT/USD ≠ 1);
     * sonst wie [convertsUsdtAsUsd]. Nie für USDT selbst.
     */
    fun convertsUsdt(currency: String, usdtMarket: Boolean): Boolean {
        val code = CurrencyConversion.normalize(currency)
        return code != "USDT" && (usdtMarket || code != "USD")
    }

    /** Tageskurse USD → USD für [convert] in USD: an jedem Tag 1 (keine Devisen-Abfrage, nie «mit heutigem Kurs»). */
    fun usdParity(fromEpochDay: Long, toEpochDay: Long): Map<Long, Double> =
        (fromEpochDay..toEpochDay).associateWith { 1.0 }

    /** Hängt an einem der Tage [days] die Umrechnung an USDT = 1 (kein USDT/USD-Kurs)? false ohne [usdt]. */
    fun usdtApproximate(usdt: UsdtUsdRates?, days: Iterable<Long>, todayEpochDay: Long): Boolean =
        usdt != null && days.any { usdt.on(it, todayEpochDay).approximate }

    /**
     * Wertverlauf in [currency] mit Tageskursen enthält die Bewegung des Devisenkurses (Punkte und Zuflüsse je zum Kurs
     * ihres Tags), ebenso «7 T»/«1 J» im Kopf, sobald dieselben Tageskurse geladen sind ([rateFunction]) — Kopf der
     * Karte: «inkl. Devisenkurs». false für USD/USDT oder mit dem heutigen Kurs ([approximate]).
     */
    fun includesFxMovement(currency: String, approximate: Boolean): Boolean = convertsUsdtAsUsd(currency) && !approximate

    /**
     * Letzter Tag einer Abfrage der Devisen-Tageskurse «bis heute»: der spätere von heute in der Gerätezeit
     * ([localToday]) und in UTC ([utcToday]) — so gilt eine Ladung bis zum nächsten UTC-Tag als aktuell ([fxLoadOutdated]),
     * auch westlich von UTC (dort ist der Gerätetag abends noch der gestrige UTC-Tag).
     */
    fun fxRequestEnd(localToday: LocalDate, utcToday: LocalDate): LocalDate = maxOf(localToday, utcToday)

    /** Geladene Devisen-Tageskurse bis [loadedTo] sind veraltet, sobald ein neuer UTC-Tag ([utcToday]) begonnen hat. */
    fun fxLoadOutdated(loadedTo: LocalDate, utcToday: LocalDate): Boolean = loadedTo.isBefore(utcToday)

    /** Zeitraum der Kursabfrage für einen Verlauf, der [days] Tage vor [today] beginnt. */
    fun requestRange(days: Int, today: LocalDate): Pair<LocalDate, LocalDate> =
        today.minusDays(days.toLong().coerceAtLeast(0) + LEAD_DAYS) to today

    /**
     * Höchstens so viele Tage je Abfrage der Devisen-Tageskurse: Frankfurter liefert längere Zeiträume nur noch
     * wöchentlich (ein Kurs je Woche) — «Seit 1. Kauf» und der Stichtag bekämen sonst Wochenkurse.
     */
    const val FX_CHUNK_DAYS = 365

    /**
     * [from]..[to] (je einschliesslich) in n = ⌈Tage / [maxDays]⌉ gleich lange Stücke (Länge unterscheidet sich höchstens
     * um einen Tag, die längeren zuerst), aufsteigend und lückenlos; das letzte endet an [to]. Gleich lang statt «volle
     * Jahre + Rest»: ein Rest von ein, zwei Tagen (Wochenende, Feiertag, heute) hätte bei Frankfurter keinen Kurs und
     * liesse die ganze Reihe scheitern. Leer, wenn [from] nach [to] liegt.
     */
    fun fxChunks(from: LocalDate, to: LocalDate, maxDays: Int = FX_CHUNK_DAYS): List<Pair<LocalDate, LocalDate>> {
        if (from.isAfter(to) || maxDays <= 0) return emptyList()
        val days = to.toEpochDay() - from.toEpochDay() + 1
        val n = (days + maxDays - 1) / maxDays
        val base = days / n
        val longer = days % n
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var start = from
        for (i in 0 until n) {
            val len = base + if (i < longer) 1 else 0
            val end = start.plusDays(len - 1)
            out += start to end
            start = end.plusDays(1)
        }
        return out
    }

    /**
     * Kann das Stück [from]..[to] Tageskurse haben? Die EZB veröffentlicht nur an Werktagen (Mo–Fr), den Kurs von heute
     * ([today]) erst am Nachmittag. Ein Stück nur aus Samstag/Sonntag bzw. nur aus heute hat oft keinen Kurs — scheitert
     * seine Abfrage, zählt es als leer statt die ganze Reihe zu verwerfen (Wochenende/heute füllt [rateOn] mit dem Vortag).
     */
    fun chunkMayHaveRates(from: LocalDate, to: LocalDate, today: LocalDate): Boolean {
        var d = from
        while (!d.isAfter(to)) {
            val weekend = d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY
            if (!weekend && d != today) return true
            d = d.plusDays(1)
        }
        return false
    }

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
     * Zahl zeigen. Mit [usdt]: × USDT/USD des Tags ([UsdtUsdRates.on]) — Faktor USDT → Zielwährung.
     */
    fun rateFunction(
        dailyRates: Map<Long, Double>?,
        currentRate: Double,
        todayEpochDay: Long,
        usdt: UsdtUsdRates? = null,
    ): (Long) -> Double {
        val current = valid(currentRate) ?: 1.0
        val sorted = dailyRates.orEmpty().entries
            .mapNotNull { (day, rate) -> valid(rate)?.let { day to it } }
            .sortedBy { it.first }
        return { day ->
            val fx = when {
                day >= todayEpochDay || sorted.isEmpty() -> current
                else -> rateOn(sorted, day) ?: sorted.first().second
            }
            if (usdt == null) fx else fx * usdt.on(day, todayEpochDay).rate
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
     * @param usdt Marktkurse USDT/USD; null = USDT gilt 1 USD (bisherige Rechnung)
     */
    fun convert(
        series: PortfolioHistorySeries,
        dailyRates: Map<Long, Double>?,
        currentRate: Double,
        todayEpochDay: Long,
        usdt: UsdtUsdRates? = null,
    ): PortfolioHistoryConverted {
        val approximate = dailyRates.orEmpty().values.none { valid(it) != null }
        if (series.points.isEmpty()) return PortfolioHistoryConverted(series, approximate)
        val rateFor = rateFunction(dailyRates, currentRate, todayEpochDay, usdt)
        val usdtApprox = usdtApproximate(
            usdt,
            series.points.map { it.epochDay } + series.cashFlows.orEmpty().map { it.epochDay } +
                series.pnlValues.map { it.epochDay } + series.pnlFlows.map { it.epochDay },
            todayEpochDay,
        )
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
            usdtApproximate = usdtApprox,
        )
    }
}
