package com.cryptochecker.app.domain.activity

/**
 * Open-Interest-Verlauf je Paar für «Ungewöhnliche Aktivität» und «Warum?» (reines Kotlin,
 * Swift-Spiegel `OiSeries`): statt einer einzigen gespeicherten Messung die letzten
 * [MAX_SAMPLES] Messungen, mindestens [MIN_SPACING_MILLIS] auseinander und höchstens
 * [MAX_AGE_MILLIS] alt. Verglichen wird der aktuelle Wert mit dem MEDIAN der Messungen, die
 * mindestens [MIN_AGE_MILLIS] alt sind — eine einzelne Ausreisser-Messung kippt so nichts.
 * Die Richtung gilt als einheitlich, wenn der aktuelle Wert über (bzw. unter) JEDER dieser
 * Messungen liegt. Messungen einer anderen Futures-Quelle zählen nicht (andere Börse = anderer
 * Bestand); alte Messungen ohne Quelle passen zu jeder.
 */
object OiSeries {

    /** So viele Messungen bleiben höchstens. */
    const val MAX_SAMPLES = 5

    /** Neue Messung frühestens so lange nach der letzten. */
    const val MIN_SPACING_MILLIS = 30 * 60_000L

    /** Ältere Messungen fallen weg. */
    const val MAX_AGE_MILLIS = 12 * ActivityAnalyzer.HOUR_MILLIS

    /** Verglichen wird nur mit Messungen, die mindestens so alt sind. */
    const val MIN_AGE_MILLIS = 30 * 60_000L

    /** Zeitpunkt bis so weit in der Zukunft gilt noch (Uhren gehen nicht genau gleich). */
    const val FUTURE_TOLERANCE_MILLIS = 60_000L

    /**
     * Ergebnis des Vergleichs.
     *
     * @param changePercent Veränderung gegenüber dem Median in %, null ohne Vergleichswert
     * @param minutes Minuten seit der ältesten verglichenen Messung
     * @param consistent aktueller Wert liegt über (bzw. unter) allen verglichenen Messungen
     * @param samples so viele Messungen wurden verglichen
     */
    data class Result(val changePercent: Double?, val minutes: Int?, val consistent: Boolean, val samples: Int)

    private val NONE = Result(null, null, consistent = false, samples = 0)

    /** Gleiche Quelle? null (alte Messung) passt zu jeder. */
    fun sameSource(a: String?, b: String?): Boolean = a == null || b == null || a == b

    private fun valid(sample: OiSample): Boolean = sample.units.isFinite() && sample.units > 0.0

    /**
     * Aufräumen: ungültige, zu alte (> [MAX_AGE_MILLIS]) und künftige Messungen weg, nach Zeit
     * sortiert, ausgedünnt auf [MIN_SPACING_MILLIS] Abstand (von der ältesten an), die jüngsten [MAX_SAMPLES].
     */
    fun prune(series: List<OiSample>, now: Long): List<OiSample> {
        val sorted = series.filter {
            valid(it) && now - it.time <= MAX_AGE_MILLIS && it.time - now <= FUTURE_TOLERANCE_MILLIS
        }.sortedBy { it.time }
        val kept = ArrayList<OiSample>(sorted.size)
        for (s in sorted) {
            val last = kept.lastOrNull()
            if (last == null || s.time - last.time >= MIN_SPACING_MILLIS) kept += s
        }
        return kept.takeLast(MAX_SAMPLES)
    }

    /**
     * Neue Messung anhängen, wenn die letzte mindestens [MIN_SPACING_MILLIS] älter ist. Messungen
     * einer anderen Quelle fallen dabei weg (der Verlauf beginnt neu).
     */
    fun append(series: List<OiSample>, sample: OiSample, now: Long): List<OiSample> {
        val base = prune(series, now).filter { sameSource(it.source, sample.source) }
        if (!valid(sample)) return base
        val last = base.lastOrNull()
        val add = last == null || sample.time - last.time >= MIN_SPACING_MILLIS
        return (if (add) base + sample else base).takeLast(MAX_SAMPLES)
    }

    /** Vergleich des aktuellen Werts [currentUnits] (Quelle [source]) mit dem Verlauf. */
    fun change(series: List<OiSample>, currentUnits: Double?, source: String?, now: Long): Result {
        if (currentUnits == null || !currentUnits.isFinite() || currentUnits <= 0.0) return NONE
        val comparable = prune(series, now).filter {
            sameSource(it.source, source) && now - it.time >= MIN_AGE_MILLIS
        }
        if (comparable.isEmpty()) return NONE
        val reference = BroadMarket.median(comparable.map { it.units })
        val change = (currentUnits / reference - 1.0) * 100.0
        val consistent = comparable.all {
            when {
                change > 0.0 -> currentUnits >= it.units
                change < 0.0 -> currentUnits <= it.units
                else -> true
            }
        }
        return Result(
            changePercent = change,
            minutes = ((now - comparable.first().time) / 60_000L).toInt(),
            consistent = consistent,
            samples = comparable.size,
        )
    }
}
