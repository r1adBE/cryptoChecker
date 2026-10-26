package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.DayChange
import kotlin.math.abs

/**
 * Plausibilitätsprüfung für Kerzenkurse im Portfolio (Wertverlauf und Stichtag-Export) — gleiche
 * Schwelle und gleiche Idee wie die Prozent-Änderung ([DayChange.MAX_PRICE_GAP]): Weicht ein Kurs
 * um mehr als 25 % vom Bezugskurs ab, gehören Kerzen und Kurs wohl nicht zum selben Coin
 * (gleiches Kürzel, anderer Token, z. B. auf einer Ausweich-Börse). Dann wird der Kerzenkurs
 * verworfen statt still verwendet. Reines Kotlin, gespiegelt in `PricePlausibility.swift`.
 *
 *  - Wertverlauf und «7 T» ([closesMatchLive]): nur IDENTITÄTSPRÜFUNG mit weitem Faktor
 *    ([TOKEN_FACTOR], Verhältnis ausserhalb 1/3…3 = anderer Token), NICHT die 25-%-Regel: Der
 *    jüngste Schluss kann bis zu 12 h alt sein (Zwischenspeicher), ein echter Tag mit 30 % Bewegung
 *    darf den Coin nicht aus dem Verlauf werfen. Der heutige Punkt nimmt ohnehin den aktuellen
 *    Kurs. Passt die Reihe nicht, zählt sie nicht (Coin unter «ohne …», auch Stablecoins ausser USDT).
 *  - Stichtag ([acceptSourceClose]): Kurs einer Quelle nur, wenn dieselbe Quelle den Coin heute
 *    als denselben Token führt (ihr jüngster Schluss gegen den aktuellen Kurs, [sameToken];
 *    Ausweich-Quellen zusätzlich ±25 %); sonst nächste Quelle. Ohne aktuellen Kurs: nicht übernommen.
 *  - Aktueller Kurs aus der letzten Stundenkerze ([candleReference], [acceptCandle]): wenn
 *    Binance-Spot den Coin nicht führt, nur bei gleichem Token wie ein unabhängiger Bezug
 *    (Merkliste oder letzter guter Portfolio-Kurs); sonst kein Kurs («—»). Ohne jeden Bezug ebenfalls
 *    kein Kurs: Der Coin heisst «ungeprüft» und zählt nirgends als Geld (Summe, «Seit Kauf», «Heute»,
 *    Portfolio-Alarm) — gleiche Regel wie der Stichtag-Export.
 *  - Ohne Bezugskurs: [matches] lässt den Kurs gelten (nicht prüfbar), die Identitätsprüfung
 *    [sameToken] dagegen NICHT — ohne Bezug ist der gleiche Token nicht bestätigt. Wertverlauf/«7 T»:
 *    Reihe ohne aktuellen Kurs zählt nicht (Coin unter «ohne …»), Stichtag ebenso («ungeprüft»),
 *    aktueller Kurs aus der Kerze ebenso ([acceptCandle]).
 */
object PricePlausibility {

    /** Grösste erlaubte Abweichung (Anteil) — dieselbe wie bei der Prozent-Änderung. */
    const val MAX_GAP = DayChange.MAX_PRICE_GAP

    /**
     * Identitätsprüfung Kerzenreihe ↔ aktueller Kurs ([closesMatchLive]): Liegt das Verhältnis
     * ausserhalb 1/[TOKEN_FACTOR] … [TOKEN_FACTOR], ist es wohl ein anderer Token.
     */
    const val TOKEN_FACTOR = 3.0

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
     * Gleicher Token? Verhältnis [value] / [reference] innerhalb 1/[TOKEN_FACTOR] … [TOKEN_FACTOR].
     * Ungültiger Wert: nein. Ohne gültigen Bezug: nein (gleicher Token nicht bestätigt).
     */
    fun sameToken(value: Double?, reference: Double?): Boolean {
        val v = valid(value) ?: return false
        val r = valid(reference) ?: return false
        val ratio = v / r
        return ratio >= 1.0 / TOKEN_FACTOR && ratio <= TOKEN_FACTOR
    }

    /**
     * Wertverlauf: Gehört die Kerzenreihe ([sortedCloses], aufsteigend nach Tag) zum aktuellen
     * Kurs [live]? Geprüft wird der aktuelle Kurs gegen den jüngsten Schluss, aber nur als
     * Identitätsprüfung ([sameToken]) — echte grosse Tagesbewegungen bleiben im Verlauf.
     * Leere Reihe: ja (nichts zu prüfen). Kein gültiger aktueller Kurs: nein — der Token ist nicht
     * bestätigt, die Reihe zählt nicht (Coin unter «ohne …»).
     */
    fun closesMatchLive(sortedCloses: List<Pair<Long, Double>>, live: Double?): Boolean {
        val last = sortedCloses.lastOrNull()?.second ?: return true
        return sameToken(live, last)
    }

    /**
     * Stichtag: Tagesschluss [close] einer Quelle übernehmen? Jede Quelle (auch Binance-Spot) muss den
     * Coin heute als denselben Token führen wie das Portfolio: ihr jüngster Schluss [sourceLatest]
     * gegen den aktuellen Portfolio-Kurs [current] — Binance-Spot mit der Identitätsprüfung
     * ([sameToken], 1/3…3, wie der Wertverlauf), Ausweich-Quellen zusätzlich mit ±25 % ([matches]).
     * Streng: ohne gültigen aktuellen Kurs oder ohne heutigen Kurs der Quelle nicht übernommen
     * (Export: Feld leer, Hinweis «ungeprüft»).
     * @param primary Binance-Spot (Quelle der aktuellen Portfolio-Kurse)
     * @param sourceLatest jüngster Schluss derselben Quelle für dasselbe Symbol (null = unbekannt)
     * @param current aktueller Portfolio-Kurs des Coins in USDT (null = unbekannt)
     */
    fun acceptSourceClose(close: Double?, primary: Boolean, sourceLatest: Double?, current: Double?): Boolean {
        if (valid(close) == null) return false
        val now = valid(current) ?: return false
        val latest = valid(sourceLatest) ?: return false
        if (!sameToken(latest, now)) return false
        return primary || matches(now, latest)
    }

    /**
     * Unabhängiger Bezug für einen Portfolio-Kurs aus der letzten Stundenkerze (Binance-Spot führt
     * den Coin nicht): der Median der Merklisten-Kurse desselben Coins gegen einen USD-Stablecoin
     * ([watchPrices], Spot — meist frischer), sonst der gemerkte letzte gute Portfolio-Kurs
     * ([stored], siehe [PortfolioPriceFallback]). Ungültige Werte zählen nicht; null = kein Bezug.
     */
    fun candleReference(watchPrices: List<Double>, stored: Double?): Double? {
        val watch = watchPrices.mapNotNull { valid(it) }.sorted()
        if (watch.isEmpty()) return valid(stored)
        val mid = watch.size / 2
        return if (watch.size % 2 == 1) watch[mid] else (watch[mid - 1] + watch[mid]) / 2.0
    }

    /**
     * Kerzenkurs [candle] als aktuellen Portfolio-Kurs übernehmen? Nur gültig und vom selben Token
     * wie der Bezug [reference] ([sameToken], Verhältnis 1/3…3) — sonst kein Kurs («—») statt einer
     * sicher wirkenden falschen Summe. Ohne Bezug (noch nie ein Kurs, keine passende Merkliste): NICHT
     * übernommen — ein ungeprüfter Kurs zählt nicht als Geld (wie der Stichtag-Export). Die Quelle nennt
     * den Coin dann «ungeprüft» ([candleUnchecked]); ein Merklisten-Paar an einer anderen Börse geht vor
     * ([PortfolioWatchPair]).
     */
    fun acceptCandle(candle: Double?, reference: Double?): Boolean = sameToken(candle, reference)

    /** Gültiger Kerzenkurs, aber kein Bezug zum Prüfen → Coin ohne Kurs, «ungeprüft». */
    fun candleUnchecked(candle: Double?, reference: Double?): Boolean =
        valid(candle) != null && valid(reference) == null
}
