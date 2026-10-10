package com.cryptochecker.app.domain.portfolio

/**
 * Darstellung des Wertverlaufs bei einer Umrechnungswährung (Umschalter «CHF | USDT | Vergleich»):
 * [CURRENCY] = in der gewählten Währung (Standard, wie bisher), [USDT] = unumgerechnet,
 * [COMPARE] = beide Kurven als Veränderung in Prozent. Gespeichert unter dem Namen (nur auf diesem
 * Gerät, nicht in der Sicherung); gespiegelt in `Shared/Portfolio/PortfolioCompare.swift`.
 */
enum class PortfolioHistoryView {
    CURRENCY,
    USDT,
    COMPARE,
    ;

    companion object {
        /** Gespeicherter Name; unbekannt oder leer = in der Währung. */
        fun fromName(name: String?): PortfolioHistoryView = entries.firstOrNull { it.name == name } ?: CURRENCY
    }
}

/**
 * Vergleich zweier Verläufe in Prozent seit dem Ausgangspunkt (beide beginnen bei 0 %):
 * [epochDays], [currency] und [usdt] gleich lang und aufsteigend nach Tag, mindestens zwei Punkte.
 */
data class PortfolioCompareSeries(
    val epochDays: List<Long>,
    /** Veränderung in der gewählten Währung in Prozent je Tag. */
    val currency: List<Double>,
    /** Veränderung in USDT in Prozent je Tag. */
    val usdt: List<Double>,
) {
    val lastIndex: Int get() = epochDays.lastIndex

    /** Währungseffekt am Tag [index] (Währung minus USDT, in Prozentpunkten); null ausserhalb. */
    fun effectAt(index: Int): Double? {
        val c = currency.getOrNull(index) ?: return null
        val u = usdt.getOrNull(index) ?: return null
        return PortfolioCompare.effect(c, u)
    }

    /** Währungseffekt am letzten Tag. */
    val effect: Double get() = PortfolioCompare.effect(currency.last(), usdt.last())
}

/**
 * Reine Rechnung zum Vergleich «in USDT +12 %, in CHF nur +7 %» (testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioCompare.swift`):
 *  - Ausgangspunkt = erster Tag, an dem BEIDE Werte grösser als 0 sind (bei «Seit 1. Kauf» kann
 *    das Portfolio am Anfang leer sein); die Tage davor fallen weg.
 *  - Prozent je Tag = (Wert / Ausgangswert − 1) × 100, je Reihe mit ihrem eigenen Ausgangswert.
 *  - Währungseffekt = Prozent in der Währung minus Prozent in USDT.
 *  - Kein Ausgangspunkt, weniger als zwei Punkte ab dort oder Reihen, die nicht Tag für Tag
 *    zusammenpassen: kein Vergleich (null) — der Umschalter blendet «Vergleich» dann aus.
 */
object PortfolioCompare {

    private fun positive(v: Double): Boolean = v.isFinite() && v > PortfolioCalculator.EPS

    /** Index des Ausgangspunkts (beide Werte > 0); null, wenn es keinen gibt. */
    fun baseIndex(currency: List<Double>, usdt: List<Double>): Int? {
        val n = minOf(currency.size, usdt.size)
        for (i in 0 until n) {
            if (positive(currency[i]) && positive(usdt[i])) return i
        }
        return null
    }

    /** Veränderung in Prozent gegenüber [base]. */
    fun percent(value: Double, base: Double): Double = (value / base - 1.0) * 100.0

    /** Währungseffekt in Prozentpunkten: Veränderung in der Währung minus Veränderung in USDT. */
    fun effect(currencyPercent: Double, usdtPercent: Double): Double = currencyPercent - usdtPercent

    /**
     * Vergleich aus dem umgerechneten Verlauf [currency] und dem unumgerechneten [usdt]
     * (gleiche Tage in gleicher Reihenfolge); null ohne sinnvollen Vergleich.
     */
    fun build(currency: List<PortfolioHistoryPoint>, usdt: List<PortfolioHistoryPoint>): PortfolioCompareSeries? {
        if (currency.size != usdt.size || currency.size < 2) return null
        for (i in currency.indices) {
            if (currency[i].epochDay != usdt[i].epochDay) return null
        }
        val base = baseIndex(currency.map { it.value }, usdt.map { it.value }) ?: return null
        if (base > currency.lastIndex - 1) return null
        val baseCurrency = currency[base].value
        val baseUsdt = usdt[base].value
        val days = ArrayList<Long>(currency.size - base)
        val c = ArrayList<Double>(currency.size - base)
        val u = ArrayList<Double>(currency.size - base)
        for (i in base..currency.lastIndex) {
            val cv = percent(currency[i].value, baseCurrency)
            val uv = percent(usdt[i].value, baseUsdt)
            // Ungültige Werte (sollte nicht vorkommen) machen den Vergleich wertlos
            if (!cv.isFinite() || !uv.isFinite()) return null
            days.add(currency[i].epochDay)
            c.add(cv)
            u.add(uv)
        }
        return PortfolioCompareSeries(days, c, u)
    }

    /** Gemeinsame Skala beider Kurven (Tief, Hoch) in Prozent; 0 % liegt immer darin. */
    fun bounds(series: PortfolioCompareSeries): Pair<Double, Double> {
        var lo = 0.0
        var hi = 0.0
        for (v in series.currency) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        for (v in series.usdt) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return lo to hi
    }

    /**
     * Welche Darstellung gilt: [saved] nur, wenn sie möglich ist — USDT braucht den unumgerechneten
     * Verlauf mit Tageskursen ([usdtAvailable]), der Vergleich zusätzlich einen Ausgangspunkt
     * ([compareAvailable]); sonst in der Währung (wie bisher).
     */
    fun effectiveView(saved: PortfolioHistoryView, usdtAvailable: Boolean, compareAvailable: Boolean): PortfolioHistoryView =
        when {
            !usdtAvailable -> PortfolioHistoryView.CURRENCY
            saved == PortfolioHistoryView.COMPARE && !compareAvailable -> PortfolioHistoryView.CURRENCY
            else -> saved
        }
}
