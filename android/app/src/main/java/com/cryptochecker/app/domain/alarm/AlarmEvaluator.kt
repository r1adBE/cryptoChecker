package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entscheidet, ob ein Alarm auslöst. Bewusst frei von Android-Abhängigkeiten,
 * damit die Regeln als Unit-Test prüfbar bleiben.
 */
@Singleton
class AlarmEvaluator @Inject constructor() {

    fun shouldTrigger(
        alarm: AlarmEntity,
        price: Double,
        previousPrice: Double?,
        now: Long,
        cooldownMinutes: Int,
    ): Boolean {
        if (!alarm.enabled) return false
        if (price <= 0.0) return false

        if (alarm.lastTriggeredAt > 0 && cooldownMinutes > 0) {
            val elapsed = now - alarm.lastTriggeredAt
            if (elapsed in 0 until cooldownMinutes * 60_000L) return false
        }

        return when (alarm.condition) {
            // Kursmarke: nur scharf (referenceAt == 0) — also beim Überschreiten, nicht bei
            // jeder Aktualisierung, solange der Kurs jenseits der Marke bleibt. Siehe [shouldRearmLevel].
            AlarmCondition.PRICE_ABOVE -> isLevelArmed(alarm) && price >= alarm.threshold
            AlarmCondition.PRICE_BELOW -> isLevelArmed(alarm) && price <= alarm.threshold
            AlarmCondition.CHANGE_PERCENT_UP -> {
                val change = changePercent(alarm, price, previousPrice) ?: return false
                change >= alarm.threshold
            }
            AlarmCondition.CHANGE_PERCENT_DOWN -> {
                val change = changePercent(alarm, price, previousPrice) ?: return false
                change <= -alarm.threshold
            }
            AlarmCondition.MOVE_PERCENT_WINDOW -> {
                // Nur innerhalb des laufenden Fensters; sonst setzt der Aufrufer neu an.
                if (needsWindowReset(alarm, now)) return false
                val reference = alarm.referencePrice ?: return false
                if (reference <= 0.0) return false
                kotlin.math.abs((price - reference) / reference * 100.0) >= alarm.threshold
            }
            // Braucht Volumendaten, siehe [shouldTriggerVolumeSpike].
            AlarmCondition.VOLUME_SPIKE -> false
            // Braucht Hoch/Tief des Zeitraums, siehe [NearExtreme.decide].
            AlarmCondition.NEAR_HIGH, AlarmCondition.NEAR_LOW -> false
        }
    }

    /**
     * Kursalarm (PRICE_ABOVE/PRICE_BELOW) scharf? Gespeichert ohne neue Spalte in
     * `referenceAt`: 0 = scharf, > 0 = schon gemeldet (Zeitpunkt), wartet auf die Rückkehr
     * des Kurses auf die andere Seite der Marke. Neu angelegt, bearbeitet, wieder
     * eingeschaltet oder aus einer Sicherung geladen → 0 (scharf): liegt der Kurs dann
     * schon jenseits der Marke, meldet der Alarm genau einmal.
     */
    fun isLevelArmed(alarm: AlarmEntity): Boolean = alarm.referenceAt <= 0L

    /**
     * Gemeldeter Kursalarm wieder scharf stellen? Erst, wenn der Kurs um die Hysterese
     * ([LEVEL_HYSTERESIS] der Marke) auf die andere Seite zurückgekehrt ist — so meldet ein
     * wiederholender Alarm, der um die Marke pendelt, nicht bei jeder Aktualisierung.
     * [price] in der Währung des Schwellwerts (wie bei [shouldTrigger]).
     */
    fun shouldRearmLevel(alarm: AlarmEntity, price: Double): Boolean {
        if (!alarm.condition.isPriceThreshold || isLevelArmed(alarm)) return false
        if (!price.isFinite() || price <= 0.0 || alarm.threshold <= 0.0) return false
        return when (alarm.condition) {
            AlarmCondition.PRICE_ABOVE -> price < alarm.threshold * (1.0 - LEVEL_HYSTERESIS)
            else -> price > alarm.threshold * (1.0 + LEVEL_HYSTERESIS)
        }
    }

    /**
     * Volumen-Spike: [ratio] = Volumen der letzten abgeschlossenen Stunde geteilt
     * durch den Schnitt der 24 Stunden davor, [candleOpenTime] = Startzeit dieser
     * Stundenkerze. Löst je Kerze höchstens einmal aus (gemerkt in referenceAt).
     */
    fun shouldTriggerVolumeSpike(
        alarm: AlarmEntity,
        ratio: Double?,
        candleOpenTime: Long,
        now: Long,
        cooldownMinutes: Int,
    ): Boolean {
        if (alarm.condition != AlarmCondition.VOLUME_SPIKE) return false
        if (!alarm.enabled) return false
        if (ratio == null || ratio.isNaN() || ratio.isInfinite()) return false
        if (candleOpenTime <= 0) return false
        if (alarm.threshold <= 0.0) return false
        // Dieselbe Kerze wurde schon gemeldet
        if (alarm.referenceAt == candleOpenTime) return false

        if (alarm.lastTriggeredAt > 0 && cooldownMinutes > 0) {
            val elapsed = now - alarm.lastTriggeredAt
            if (elapsed in 0 until cooldownMinutes * 60_000L) return false
        }

        return ratio >= alarm.threshold
    }

    /**
     * Bewegungs-Alarm ohne gültiges Fenster (noch kein Bezugskurs oder das
     * Fenster ist abgelaufen): Der Aufrufer setzt den aktuellen Kurs als
     * neuen Bezug und beginnt ein neues Fenster.
     */
    fun needsWindowReset(alarm: AlarmEntity, now: Long): Boolean {
        if (alarm.condition != AlarmCondition.MOVE_PERCENT_WINDOW) return false
        if (alarm.referencePrice == null || alarm.referenceAt <= 0) return true
        return now - alarm.referenceAt > alarm.windowHours.coerceAtLeast(1) * 3_600_000L
    }

    /**
     * Veränderung gegenüber dem Bezugskurs. Fehlt dieser, wird der zuletzt
     * bekannte Kurs verwendet; ohne beides kann kein Prozentalarm greifen.
     */
    fun changePercent(alarm: AlarmEntity, price: Double, previousPrice: Double?): Double? {
        val reference = alarm.referencePrice ?: previousPrice ?: return null
        if (reference <= 0.0) return null
        return (price - reference) / reference * 100.0
    }

    companion object {
        /** Hysterese der Kursalarme: 0,2 % der Marke (Spiegel: AlarmLogic.swift). */
        const val LEVEL_HYSTERESIS = 0.002
    }
}
