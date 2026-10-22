package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity

/**
 * Gespeicherter Alarm aus den Eingaben des Editors und dem GESPEICHERTEN Stand (seit Build 102): Der bestehende Alarm
 * wird in derselben Transaktion gelesen, in der gespeichert wird (WatchRepository.saveAlarmMerged). Vorher kam er aus
 * dem Zwischenstand der Oberfläche — hatte eine Aktualisierung den Alarm eben ausgelöst, überschrieb das Speichern
 * diesen Zustand mit dem alten (scharf) und er meldete ein zweites Mal. Reines Kotlin.
 */
object AlarmSaveBuilder {

    /** Eingaben des Editors; [freshReference] = frischer Kurs als Bezug neuer Prozentalarme ([AlarmEdit.freshReference]). */
    data class Input(
        val id: Long,
        val watchId: Long,
        val condition: AlarmCondition,
        val threshold: Double,
        val repeating: Boolean,
        val sound: Boolean,
        val vibrate: Boolean,
        val speak: Boolean,
        val windowHours: Int,
        val currency: String?,
        val freshReference: Double?,
        val now: Long,
    )

    /** Zu speichernder Alarm: Eingaben, zusammengeführt mit [existing] ([AlarmEdit.merged]). */
    fun build(input: Input, existing: AlarmEntity?): AlarmEntity {
        val keepReference = existing != null && existing.condition == input.condition &&
            existing.windowHours == input.windowHours
        val referencePrice = when {
            !input.condition.isPercent -> null
            // Prozentalarme messen ab dem Kurs, der beim Anlegen galt.
            keepReference -> existing?.referencePrice
            // Nur ein frischer Kurs taugt als Bezug; sonst setzt ihn die nächste Abfrage
            else -> input.freshReference
        }
        // Bewegungs-Alarm: Fenster beginnt jetzt (oder läuft unverändert weiter).
        // Volumen-Spike: zuletzt gemeldete Kerze behalten, damit sie nicht nochmals meldet.
        val referenceAt = when {
            input.condition == AlarmCondition.VOLUME_SPIKE ->
                existing?.takeIf { it.condition == AlarmCondition.VOLUME_SPIKE }?.referenceAt ?: 0L
            input.condition != AlarmCondition.MOVE_PERCENT_WINDOW -> 0L
            keepReference -> existing?.referenceAt ?: 0L
            referencePrice != null -> input.now
            else -> 0L
        }
        val edited = AlarmEntity(
            id = input.id,
            watchId = input.watchId,
            condition = input.condition,
            threshold = input.threshold,
            enabled = true,
            repeating = input.repeating,
            sound = input.sound,
            vibrate = input.vibrate,
            speak = input.speak,
            referencePrice = referencePrice,
            lastTriggeredAt = 0,
            lastTriggeredPrice = null,
            windowHours = input.windowHours,
            referenceAt = referenceAt,
            currency = input.currency,
        )
        // Nur Ton, Vibration, Ansage oder Wiederholung geändert: Zustand (ausgelöst/gemeldet,
        // Bezug, Ein/Aus) bleibt; neu scharf nur bei geänderter Bedingung ([AlarmEdit.merged])
        return AlarmEdit.merged(existing, edited)
    }
}
