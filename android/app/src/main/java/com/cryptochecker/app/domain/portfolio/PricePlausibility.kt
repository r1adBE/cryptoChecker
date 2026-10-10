package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.DayChange
import kotlin.math.abs

/**
 * Plausibilitätsprüfung für Kerzenkurse im Portfolio (Wertverlauf und Stichtag-Export) — gleiche
 * Schwelle und gleiche Idee wie die Prozent-Pille ([DayChange.MAX_PRICE_GAP]): Weicht ein Kurs
 * um mehr als 25 % vom Bezugskurs ab, gehören Kerzen und Kurs wohl nicht zum selben Coin
 * (gleiches Kürzel, anderer Token, z. B. auf einer Ausweich-Börse). Dann wird der Kerzenkurs
 * verworfen statt still verwendet. Reines Kotlin, gespiegelt in `PricePlausibility.swift`.
 *
 *  - Wertverlauf ([closesMatchLive]): jüngster Tagesschluss gegen den aktuellen Kurs. Passt er
 *    nicht, zählt die ganze Kerzenreihe des Coins nicht (Coin steht dann unter «ohne …»;
 *    Stablecoins fallen auf 1 zurück).
 *  - Stichtag ([acceptSourceClose]): Kurs einer Ausweich-Quelle (Futures, Binance.US, Coinbase)
 *    nur, wenn dieselbe Quelle den Coin heute zum aktuellen Portfolio-Kurs führt (ihr jüngster
 *    Schluss gegen den aktuellen Kurs); sonst nächste Quelle. Binance-Spot ist die Quelle der
 *    aktuellen Kurse selbst und braucht die Prüfung nicht.
 *  - Ohne Bezugskurs (aktueller Kurs unbekannt) ist keine Prüfung möglich — der Kurs gilt.
 */
object PricePlausibility {

    /** Grösste erlaubte Abweichung (Anteil) — dieselbe wie bei der Prozent-Pille. */
    const val MAX_GAP = DayChange.MAX_PRICE_GAP

    private fun valid(v: Double?): Double? = v?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * Passt [value] zum Bezug [reference] (|value / reference − 1| ≤ [MAX_GAP])?
     * Ungültiger Wert: nein. Ohne gültigen Bezug: ja (nicht prüfbar).
     */
    fun matches(value: Double?, reference: Double?): Boolean {
        val v = valid(value) ?: return false
        val r = valid(reference) ?: return true
        return abs(v / r - 1.0) <= MAX_GAP
    }

    /**
     * Wertverlauf: Gehört die Kerzenreihe ([sortedCloses], aufsteigend nach Tag) zum aktuellen
     * Kurs [live]? Geprüft wird wie bei der Pille der aktuelle Kurs gegen den jüngsten Schluss.
     * Leere Reihe oder kein aktueller Kurs: ja.
     */
    fun closesMatchLive(sortedCloses: List<Pair<Long, Double>>, live: Double?): Boolean {
        val last = sortedCloses.lastOrNull()?.second ?: return true
        if (valid(live) == null) return true
        return matches(live, last)
    }

    /**
     * Stichtag: Tagesschluss [close] einer Quelle übernehmen?
     * @param trusted Quelle der aktuellen Portfolio-Kurse selbst (Binance-Spot) — keine Prüfung
     * @param sourceLatest jüngster Schluss derselben Quelle für dasselbe Symbol (null = unbekannt)
     * @param current aktueller Portfolio-Kurs des Coins in USDT (null = unbekannt)
     */
    fun acceptSourceClose(close: Double?, trusted: Boolean, sourceLatest: Double?, current: Double?): Boolean {
        if (valid(close) == null) return false
        if (trusted || valid(current) == null) return true
        // Prüfung nötig, aber die Quelle nennt keinen heutigen Kurs: lieber verwerfen
        val latest = valid(sourceLatest) ?: return false
        return matches(current, latest)
    }
}
