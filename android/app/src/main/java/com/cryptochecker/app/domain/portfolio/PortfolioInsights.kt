package com.cryptochecker.app.domain.portfolio

import kotlin.math.abs

/**
 * Ein Teil der «Aufteilung»: Coin ([coin] null = «Andere», alle übrigen zusammen),
 * Wert in USDT und Anteil am Gesamtwert (0–100).
 */
data class AllocationSlice(val coin: String?, val valueUsd: Double, val sharePercent: Double) {
    val isOther: Boolean get() = coin == null
}

/**
 * Eine der «Grössten Bewegungen»: Wertänderung der Position in USDT über die gewählte
 * %-Basis (Bestand × Kursänderung) und die Kursänderung in Prozent.
 */
data class PortfolioMover(val coin: String, val changeUsd: Double, val changePercent: Double)

/**
 * Reine Regeln für «Aufteilung» und «Grösste Bewegungen» im Portfolio-Tab und für
 * «Beträge verbergen» (ohne Android, getestet in PortfolioInsightsTest; Swift-Spiegel
 * `PortfolioInsights.swift`).
 */
object PortfolioInsights {

    /** So viele Coins zeigt die Aufteilung einzeln, der Rest wird zu «Andere». */
    const val ALLOCATION_TOP = 4

    /** So viele Positionen zeigt «Grösste Bewegungen». */
    const val MOVERS_TOP = 3

    /** Platzhalter für verborgene Beträge. */
    const val HIDDEN = "•••"

    /** [text] oder [HIDDEN], wenn Beträge verborgen sind. */
    fun mask(text: String, hidden: Boolean): String = if (hidden) HIDDEN else text

    /**
     * Aufteilung nach Wert: die [top] wertvollsten Coins (absteigend, gleicher Wert nach Kürzel),
     * alle übrigen zusammen als «Andere» (nur, wenn es mehr als [top] gibt und sie zusammen
     * etwas wert sind). Coins ohne Kurs oder ohne Wert zählen nicht mit. Anteile am Wert aller
     * gezählten Coins; leer, wenn nichts einen Wert hat.
     */
    fun allocation(open: List<CoinPosition>, top: Int = ALLOCATION_TOP): List<AllocationSlice> {
        val priced = open.mapNotNull { p -> p.value?.takeIf { it.isFinite() && it > 0.0 }?.let { p.coin to it } }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        val total = priced.sumOf { it.second }
        if (priced.isEmpty() || !(total > 0.0)) return emptyList()
        val count = top.coerceAtLeast(1)
        val shown = priced.take(count).map { (coin, value) -> AllocationSlice(coin, value, share(value, total)) }
        val rest = priced.drop(count).sumOf { it.second }
        return if (priced.size > count && rest > 0.0) shown + AllocationSlice(null, rest, share(rest, total)) else shown
    }

    private fun share(value: Double, total: Double): Double = (value / total * 100.0).coerceIn(0.0, 100.0)

    /**
     * Wertänderung einer Position mit heutigem Wert [valueNow] bei einer Kursänderung von
     * [percent] %: Wert damals = Wert / (1 + p/100), Änderung = jetzt − damals. null bei
     * ungültigen Werten (oder −100 % und weniger).
     */
    fun valueChange(valueNow: Double, percent: Double): Double? {
        if (!valueNow.isFinite() || !percent.isFinite() || percent <= -100.0) return null
        val then = valueNow / (1.0 + percent / 100.0)
        return (valueNow - then).takeIf { it.isFinite() }
    }

    /**
     * Die [top] Positionen mit der grössten Wertänderung (Betrag, unabhängig von der Richtung;
     * gleich gross nach Kürzel) — aus der Kursänderung je Coin [changes] (gleiche %-Basis wie
     * «heute»). Ohne Kurs, ohne Kursänderung oder ohne Bewegung (unter einem halben Cent)
     * erscheint ein Coin nicht.
     */
    fun movers(open: List<CoinPosition>, changes: Map<String, Double>, top: Int = MOVERS_TOP): List<PortfolioMover> =
        open.mapNotNull { p ->
            val value = p.value?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            val percent = changes[p.coin] ?: changes[p.coin.uppercase()] ?: return@mapNotNull null
            val change = valueChange(value, percent) ?: return@mapNotNull null
            if (abs(change) < 0.005) null else PortfolioMover(p.coin, change, percent)
        }
            .sortedWith(compareByDescending<PortfolioMover> { abs(it.changeUsd) }.thenBy { it.coin })
            .take(top.coerceAtLeast(0))
}
