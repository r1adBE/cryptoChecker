package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.activity.HourCandle
import kotlin.math.max

/**
 * Alarm «Nahe am Hoch / Tief»: Abstand des aktuellen Kurses zum Hoch bzw. Tief der
 * letzten 30 / 90 / 365 Tage (Tageskerzen). Reines Kotlin, damit die Regeln als
 * Unit-Test prüfbar bleiben (Spiegel: Shared/Services/NearExtreme.swift).
 *
 * Gespeichert wird ohne neue Spalten:
 *  - `threshold` = Abstand in Prozent (Standard 2 %)
 *  - `windowHours` = Zeitraum in TAGEN (30, 90, 365; alles andere gilt als 30)
 *  - `referenceAt` = 0 → scharf; > 0 → schon gemeldet (Zeitpunkt), wartet auf Wiederscharfstellung
 *  - `referencePrice` = zuletzt gemeldete Marke (Hoch/Tief bzw. Kurs beim neuen Hoch/Tief); bleibt
 *    beim Wiederscharfstellen erhalten, siehe [reportedMark]
 *
 * Wiederscharfstellung: Wer gemeldet hat, meldet erst wieder, wenn sich der Kurs
 * deutlich aus der Zone entfernt hat ([rearmDistance]) — oder bei einem weiteren,
 * deutlich höheren Hoch (tieferen Tief, [furtherStepPercent]), frühestens 1 h nach der letzten Meldung. Darüber liegt wie bei allen Alarmen
 * die Pause zwischen Alarmen (Cooldown); einmalige Alarme schalten sich ab.
 */
object NearExtreme {

    /** Seite des Alarms. */
    enum class Side { HIGH, LOW }

    /** Wählbare Zeiträume in Tagen. */
    val WINDOWS: List<Int> = listOf(30, 90, 365)
    const val DEFAULT_WINDOW_DAYS = 30
    const val DEFAULT_DISTANCE_PERCENT = 2.0

    /**
     * Abstand 0 = nur neue Hochs/Tiefs melden (Vorlage «Neues 30-Tage-Hoch»), keine
     * Annäherung. Gespeichert wie jeder Abstand in `threshold`; ältere Versionen melden damit nie.
     */
    const val NEW_ONLY_DISTANCE = 0.0

    /** Alarm meldet nur neue Hochs/Tiefs ([NEW_ONLY_DISTANCE])? */
    fun isNewOnly(thresholdPercent: Double): Boolean = thresholdPercent == NEW_ONLY_DISTANCE

    /** Zwischenspeicher der Fenster-Hochs/-Tiefs je Paar. */
    const val CACHE_MILLIS = 6 * 60 * 60_000L

    /** Tageskerzen für das längste Fenster plus laufender Tag, mit Reserve. */
    const val CANDLE_LIMIT = 370

    private const val DAY_MILLIS = 24 * 60 * 60_000L

    /** Zeitraum aus dem gespeicherten Feld; Unbekanntes (z. B. Standard 1 der Spalte) → 30 Tage. */
    fun windowDays(stored: Int): Int = if (stored in WINDOWS) stored else DEFAULT_WINDOW_DAYS

    /** Hoch und Tief eines Zeitraums (Währung der Kerzen). */
    data class Range(val high: Double, val low: Double) {
        val isValid: Boolean get() = high.isFinite() && low.isFinite() && low > 0.0 && high >= low

        /** In eine andere Währung umrechnen (Faktor > 0). */
        fun scaled(factor: Double): Range = Range(high * factor, low * factor)
    }

    /**
     * Hoch/Tief der letzten [days] ABGESCHLOSSENEN Tageskerzen (die laufende zählt nicht:
     * ein Kurs darüber ist dann ein «neues Hoch»). null, wenn die Reihe zu kurz ist —
     * mindestens 90 % des Zeitraums (höchstens 300 Tage verlangt; Coinbase liefert nicht mehr),
     * sonst wäre ein «30-Tage-Hoch» bei einem frisch gelisteten Coin irreführend.
     */
    fun range(candles: List<HourCandle>, days: Int, now: Long): Range? {
        if (days <= 0) return null
        val completed = candles
            .filter { it.openTime + DAY_MILLIS <= now && it.high > 0.0 && it.low > 0.0 }
            .sortedBy { it.openTime }
            .takeLast(days)
        val needed = (minOf(days, 300) * 9 + 9) / 10
        if (completed.size < max(needed, 2)) return null
        return Range(completed.maxOf { it.high }, completed.minOf { it.low }).takeIf { it.isValid }
    }

    /** Ergebnis der Prüfung. */
    sealed interface Decision {
        /** Nichts tun. */
        data object None : Decision

        /** Alarm wieder scharf stellen (referenceAt = 0; die gemeldete Marke in referencePrice bleibt). */
        data object Rearm : Decision

        /**
         * Melden. [newExtreme] = neues Hoch/Tief (Kurs jenseits der Marke),
         * [distancePercent] = Abstand zur Marke in Prozent (immer ≥ 0),
         * [extreme] = Hoch bzw. Tief des Zeitraums, [level] = neu zu merkende Marke.
         */
        data class Fire(
            val newExtreme: Boolean,
            val distancePercent: Double,
            val extreme: Double,
            val level: Double,
        ) : Decision
    }

    /** Mindestabstand zwischen zwei Meldungen «weiteres neues Hoch/Tief» desselben Alarms. */
    const val FURTHER_EXTREME_MIN_MILLIS = 60 * 60_000L

    /**
     * Ein weiteres neues Hoch/Tief meldet erst, wenn der Kurs die zuletzt gemeldete Marke um
     * mindestens so viele Prozent übertrifft: max(Schwelle / 2, 0,5 %).
     */
    fun furtherStepPercent(thresholdPercent: Double): Double = max(thresholdPercent * 0.5, 0.5)

    /** Abstand (Prozentpunkte) jenseits der Schwelle, ab dem ein gemeldeter Alarm wieder scharf wird. */
    fun rearmDistance(thresholdPercent: Double): Double = thresholdPercent + max(thresholdPercent * 0.5, 0.5)

    /**
     * Gemeldete Marke, die noch zählt: [lastLevel] (referencePrice), solange die letzte Meldung
     * ([lastTriggeredAt]) jünger als der Zeitraum ([windowDays]) ist — sonst null. Hoch/Tief kommen
     * nur aus ABGESCHLOSSENEN Tageskerzen (6 h zwischengespeichert): Ohne die Marke meldete ein
     * wieder scharfer «Neues Hoch»-Alarm denselben Tag nochmals, sobald der Kurs wieder über das
     * alte Hoch steigt (101k gemeldet, 99,4k scharf, 100,1k erneut «neu»).
     */
    fun reportedMark(lastLevel: Double?, lastTriggeredAt: Long, windowDays: Int, now: Long): Double? {
        val level = lastLevel?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (lastTriggeredAt <= 0L) return null
        val age = now - lastTriggeredAt
        return if (age < windowDays(windowDays) * DAY_MILLIS) level else null
    }

    /**
     * @param armed true, wenn noch nicht gemeldet (referenceAt == 0)
     * @param lastLevel zuletzt gemeldete Marke, die noch zählt ([reportedMark]), null = keine
     * @param inCooldown true, solange die allgemeine Pause zwischen Alarmen läuft
     * @param lastTriggeredAt letzte Meldung dieses Alarms (Epoch-ms, 0 = nie)
     * @param now jetzt (Epoch-ms)
     */
    fun decide(
        side: Side,
        price: Double,
        range: Range,
        thresholdPercent: Double,
        armed: Boolean,
        lastLevel: Double?,
        inCooldown: Boolean,
        lastTriggeredAt: Long,
        now: Long,
    ): Decision {
        if (!price.isFinite() || price <= 0.0 || !range.isValid) return Decision.None
        // Abstand 0 = nur neue Hochs/Tiefs ([NEW_ONLY_DISTANCE]); negativ/ungültig = nichts
        if (!thresholdPercent.isFinite() || thresholdPercent < 0.0) return Decision.None

        val extreme = if (side == Side.HIGH) range.high else range.low
        val beyond = if (side == Side.HIGH) price > extreme else price < extreme
        // Abstand in Prozent der Marke, immer ≥ 0
        val distance = kotlin.math.abs(price - extreme) / extreme * 100.0

        if (beyond) {
            // Neues Hoch/Tief: scharf (und jenseits der gemeldeten Marke: «neu» heisst über
            // max(Hoch des Zeitraums, gemeldete Marke)) — oder deutlich weiter als die zuletzt
            // gemeldete Marke (max(Schwelle/2, 0,5 %)) UND frühestens 1 h nach der letzten Meldung,
            // damit eine Rally nicht bei jeder Aktualisierung meldet.
            val further = when {
                lastLevel == null || lastLevel <= 0.0 -> true
                armed -> if (side == Side.HIGH) price > lastLevel else price < lastLevel
                lastTriggeredAt > 0 && now - lastTriggeredAt in 0 until FURTHER_EXTREME_MIN_MILLIS -> false
                side == Side.HIGH -> (price - lastLevel) / lastLevel * 100.0 >= furtherStepPercent(thresholdPercent)
                else -> (lastLevel - price) / lastLevel * 100.0 >= furtherStepPercent(thresholdPercent)
            }
            if (!further || inCooldown) return Decision.None
            return Decision.Fire(newExtreme = true, distancePercent = distance, extreme = extreme, level = price)
        }

        if (thresholdPercent > 0.0 && distance <= thresholdPercent) {
            if (!armed || inCooldown) return Decision.None
            return Decision.Fire(newExtreme = false, distancePercent = distance, extreme = extreme, level = extreme)
        }

        // Deutlich ausserhalb der Zone: gemeldeten Alarm wieder scharf stellen
        if (!armed && distance > rearmDistance(thresholdPercent)) return Decision.Rearm
        return Decision.None
    }

    /** Cooldown wie in AlarmEvaluator: läuft, solange seit dem letzten Auslösen weniger Zeit verging. */
    fun inCooldown(lastTriggeredAt: Long, now: Long, cooldownMinutes: Int): Boolean {
        if (lastTriggeredAt <= 0 || cooldownMinutes <= 0) return false
        val elapsed = now - lastTriggeredAt
        return elapsed in 0 until cooldownMinutes * 60_000L
    }
}
