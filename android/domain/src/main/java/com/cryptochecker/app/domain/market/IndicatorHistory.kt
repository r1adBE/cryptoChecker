package com.cryptochecker.app.domain.market

import java.time.LocalDate

/**
 * Einordnung der Bitcoin-Kennzahlen in ihren eigenen Verlauf (reines Kotlin, testbar; Swift-Spiegel
 * `IndicatorHistoryMath` in Shared/Insights/IndicatorHistory.swift). Nur Rechnung, keine Prognose:
 *
 *  - Perzentil: An wie viel Prozent der Tage seit Beginn des Verlaufs lag der Wert tiefer als heute
 *    ([IndicatorStat.percentileBelow]). Verlauf = BTC-Tagesschlusskurse von Coin Metrics seit Juni 2016
 *    (Daten-Spiegel `btcprice.txt`, wie der Zyklus-Vergleich).
 *  - Bei gleichem Kurs: Wert in 30 Tagen, wenn der Kurs bis dahin genau gleich bliebe
 *    ([IndicatorStat.flat30]) — zeigt, wohin die gleitenden Durchschnitte rechnerisch laufen.
 *
 * Nur Kennzahlen aus dem Kurs: Mayer Multiple, Abstand zum Hoch, 30 Tage, Pi-Cycle-Abstand. On-Chain-Werte
 * (MVRV, Puell, Hashrate) haben in der App nur rund 14 Monate Verlauf — zu kurz für eine faire Einordnung.
 */
object IndicatorHistoryMath {

    /** Höchster Tagesschluss vor Juni 2016 (Nov. 2013, Coin Metrics), damit der Abstand zum Hoch 2016 stimmt. */
    const val ATH_BEFORE_2016 = 1_151.0

    /** So viele Tage reicht die Rechnung «bei gleichem Kurs» voraus. */
    const val FLAT_DAYS = 30

    /** Mindestanzahl Tage mit Wert für ein Perzentil. */
    const val MIN_DAYS = 365

    /**
     * @param closes Tagesschlusskurse in USD, aufsteigend nach Datum
     * @param current heutige Werte je Kennzahl (wie angezeigt, [BitcoinIndicatorModel])
     * @param price aktueller Kurs (für die Rechnung «bei gleichem Kurs»)
     */
    fun compute(closes: List<Pair<LocalDate, Double>>, current: Map<IndicatorId, Double?>, price: Double): IndicatorHistory? {
        val clean = closes.filter { it.second > 0.0 && it.second.isFinite() }
        if (clean.size < 400 || !(price > 0.0)) return null
        val values = clean.map { it.second }
        val n = values.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
        fun sma(end: Int, len: Int): Double? = if (end + 1 >= len) (prefix[end + 1] - prefix[end + 1 - len]) / len else null

        val mayer = ArrayList<Double>()
        val ath = ArrayList<Double>()
        val change = ArrayList<Double>()
        val pi = ArrayList<Double>()
        var runningMax = ATH_BEFORE_2016
        var firstDay: LocalDate? = null
        for (i in 0 until n) {
            val p = values[i]
            runningMax = maxOf(runningMax, p)
            ath += (1.0 - p / runningMax) * 100.0
            sma(i, 200)?.let { mayer += p / it; if (firstDay == null) firstDay = clean[i].first }
            if (i >= 30) change += (p / values[i - 30] - 1.0) * 100.0
            val s111 = sma(i, 111)
            val s350 = sma(i, 350)
            if (s111 != null && s350 != null) pi += (s111 / (2.0 * s350) - 1.0) * 100.0
        }
        val since = firstDay ?: return null
        val stats = HashMap<IndicatorId, IndicatorStat>()
        fun put(id: IndicatorId, series: List<Double>, flat: Double?) {
            val value = current[id] ?: return
            stats[id] = IndicatorStat(percentileBelow(series, value), flat)
        }
        put(IndicatorId.MAYER, mayer, flatMayer(values, price))
        put(IndicatorId.ATH_DISTANCE, ath, null)
        put(IndicatorId.CHANGE_30D, change, null)
        put(IndicatorId.PI_CYCLE_GAP, pi, flatPiGap(values, price))
        return IndicatorHistory(since, stats)
    }

    /** Anteil der Werte in [series], die kleiner als [value] sind, in Prozent; null bei zu kurzem Verlauf. */
    fun percentileBelow(series: List<Double>, value: Double): Double? {
        val usable = series.filter { it.isFinite() }
        if (usable.size < MIN_DAYS || !value.isFinite()) return null
        return usable.count { it < value } * 100.0 / usable.size
    }

    /** Mayer Multiple in [FLAT_DAYS] Tagen bei gleichem Kurs: Kurs ÷ 200-Tage-Schnitt der um 30× Kurs verlängerten Reihe. */
    fun flatMayer(values: List<Double>, price: Double): Double? {
        val sma = flatSma(values, price, 200) ?: return null
        return price / sma
    }

    /** Pi-Cycle-Abstand in [FLAT_DAYS] Tagen bei gleichem Kurs. */
    fun flatPiGap(values: List<Double>, price: Double): Double? {
        val s111 = flatSma(values, price, 111) ?: return null
        val s350 = flatSma(values, price, 350) ?: return null
        return (s111 / (2.0 * s350) - 1.0) * 100.0
    }

    /** Schnitt über die letzten [len] Tage, nachdem [FLAT_DAYS] Tage mit [price] angehängt wurden. */
    private fun flatSma(values: List<Double>, price: Double, len: Int): Double? {
        val keep = len - FLAT_DAYS
        if (keep < 0 || values.size < keep) return null
        return (values.takeLast(keep).sum() + FLAT_DAYS * price) / len
    }
}
