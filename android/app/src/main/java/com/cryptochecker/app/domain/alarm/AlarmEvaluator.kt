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
        /** Kursverlauf des Paars für MOVE_PERCENT_WINDOW (gleitendes Fenster, siehe [MoveWindow]). */
        moveHistory: List<MoveWindow.PricePoint> = emptyList(),
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
                // Ohne gültigen Bezug setzt der Aufrufer erst einen ([needsReference]).
                if (needsWindowReset(alarm, now)) return false
                // Gleitendes Fenster: grösste Bewegung gegenüber Verlauf und Bezug seit referenceAt
                val change = MoveWindow.changePercent(
                    history = moveHistory,
                    reference = MoveWindow.PricePoint(alarm.referencePrice ?: return false, alarm.referenceAt),
                    price = price,
                    hours = alarm.windowHours,
                    since = alarm.referenceAt,
                    now = now,
                ) ?: return false
                kotlin.math.abs(change) >= alarm.threshold
            }
            // Braucht Volumendaten, siehe [shouldTriggerVolumeSpike].
            AlarmCondition.VOLUME_SPIKE -> false
            // Braucht Hoch/Tief des Zeitraums, siehe [NearExtreme.decide].
            AlarmCondition.NEAR_HIGH, AlarmCondition.NEAR_LOW -> false
            // Braucht Funding/Open Interest, siehe [DerivativesAlarm.decide].
            AlarmCondition.FUNDING_ABOVE, AlarmCondition.FUNDING_BELOW,
            AlarmCondition.OI_UP, AlarmCondition.OI_DOWN -> false
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
     * Bewegungs-Alarm ohne gültigen Bezug (noch keiner, oder der Bezug liegt in der Zukunft —
     * z. B. nach einer Zeitumstellung des Geräts): Der Aufrufer setzt den aktuellen Kurs als Bezug.
     * Ein ALTER Bezug braucht keinen Neubeginn mehr — das Fenster gleitet ([MoveWindow]); vorher
     * setzte der Ablauf des Fensters den Bezug zurück, ohne die Bewegung zu prüfen (meldete im
     * Hintergrund-Takt nie).
     */
    fun needsWindowReset(alarm: AlarmEntity, now: Long): Boolean {
        if (alarm.condition != AlarmCondition.MOVE_PERCENT_WINDOW) return false
        val reference = alarm.referencePrice
        if (reference == null || !reference.isFinite() || reference <= 0.0 || alarm.referenceAt <= 0) return true
        return alarm.referenceAt - now > 60_000L
    }

    /**
     * Braucht der Alarm zuerst einen Bezugskurs? Bewegungs-Alarm siehe [needsWindowReset];
     * Prozentalarm (CHANGE_PERCENT_*) ohne Bezug — etwa angelegt, bevor das Paar einen Kurs hatte:
     * der Aufrufer setzt den aktuellen Kurs als Bezug (und meldet diesmal nicht), sonst verglich
     * der Alarm nur aufeinanderfolgende Kurse.
     */
    fun needsReference(alarm: AlarmEntity, now: Long): Boolean = when (alarm.condition) {
        AlarmCondition.MOVE_PERCENT_WINDOW -> needsWindowReset(alarm, now)
        AlarmCondition.CHANGE_PERCENT_UP, AlarmCondition.CHANGE_PERCENT_DOWN -> {
            val reference = alarm.referencePrice
            reference == null || !reference.isFinite() || reference <= 0.0
        }
        else -> false
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

        /**
         * Zustand nach dem Auslösen (Spiegel: `AlarmEvaluator.triggered` in AlarmLogic.swift):
         * letzte Meldung merken; Prozentalarme messen ab [price] weiter; «Nahe am Hoch/Tief» merkt die
         * gemeldete Marke ([nearLevel]); Bewegungs-Alarm: neues Fenster ab jetzt; Volumen-Spike: die
         * gemeldete Kerze ([candleOpenTime]); Kursmarken, «Nahe am Hoch/Tief», Funding und Open
         * Interest: gemeldet (`referenceAt` > 0) bis zur Wiederscharfstellung; einmalige Alarme aus.
         */
        fun triggered(
            alarm: AlarmEntity,
            price: Double,
            time: Long,
            /** Volumen-Spike: Startzeit der gemeldeten Stundenkerze. */
            candleOpenTime: Long? = null,
            /** «Nahe am Hoch/Tief»: gemeldete Marke (Hoch/Tief bzw. Kurs beim neuen Hoch/Tief). */
            nearLevel: Double? = null,
        ): AlarmEntity = alarm.copy(
            lastTriggeredAt = time,
            lastTriggeredPrice = price,
            referencePrice = when {
                alarm.condition.isNearExtreme -> nearLevel ?: price
                alarm.condition.isPercent -> price
                else -> alarm.referencePrice
            },
            referenceAt = when (alarm.condition) {
                AlarmCondition.VOLUME_SPIKE -> candleOpenTime ?: alarm.referenceAt
                AlarmCondition.CHANGE_PERCENT_UP, AlarmCondition.CHANGE_PERCENT_DOWN -> alarm.referenceAt
                else -> time
            },
            enabled = alarm.repeating,
        )
    }
}
