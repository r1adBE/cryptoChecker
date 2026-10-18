package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import kotlin.math.abs
import kotlin.math.max

/**
 * Regeln beim Speichern eines Alarms aus dem Editor (Spiegel: AlarmEdit.swift):
 * Löst er sofort aus ([firesNow])? Wird er neu scharf gestellt ([rearms]) oder behält er
 * seinen Zustand ([merged])?
 */
object AlarmEdit {

    /**
     * Bezugskurs eines neuen Prozentalarms: der letzte Kurs, aber nur, wenn er frisch ist
     * ([lastUpdate] höchstens [staleAfterMillis] alt, Regel «veraltet» der Merkliste). Sonst null —
     * die nächste Abfrage setzt dann den Bezug ([AlarmEvaluator.needsReference]), statt ab einem
     * alten Kurs zu messen und gleich zu melden. Spiegel: AlarmEdit.swift.
     */
    fun freshReference(lastPrice: Double?, lastUpdate: Long, now: Long, staleAfterMillis: Long): Double? {
        val price = lastPrice?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (lastUpdate <= 0L || now - lastUpdate > staleAfterMillis) return null
        return price
    }

    /**
     * Kursmarke schon erreicht? «über»: Kurs ≥ Marke, «unter»: Kurs ≤ Marke (wie
     * [AlarmEvaluator.shouldTrigger]). [price] in der Währung des Schwellwerts (bei eigener
     * Alarmwährung also schon umgerechnet); ohne gültigen Kurs false. Andere Bedingungen: false.
     */
    fun firesNow(condition: AlarmCondition, threshold: Double?, price: Double?): Boolean {
        val t = threshold?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        return when (condition) {
            AlarmCondition.PRICE_ABOVE -> p >= t
            AlarmCondition.PRICE_BELOW -> p <= t
            else -> false
        }
    }

    /**
     * Bedingung geändert? Paar, Bedingung, Schwellwert, Währung — und das Zeitfenster, wo es zur
     * Bedingung gehört (Bewegung in y Stunden, Hoch/Tief-Zeitraum, Open-Interest-Fenster).
     * Ton, Vibration, Ansage und «Einmal/Jedes Mal» zählen nicht.
     */
    fun conditionChanged(old: AlarmEntity, new: AlarmEntity): Boolean =
        old.watchId != new.watchId ||
            old.condition != new.condition ||
            !sameThreshold(old.threshold, new.threshold) ||
            normalizedCurrency(old.currency) != normalizedCurrency(new.currency) ||
            (usesWindow(new.condition) && old.windowHours != new.windowHours)

    /**
     * Wird der Alarm beim Speichern (neu) scharf — neu angelegt, Bedingung geändert oder
     * wieder eingeschaltet ([reenables])? Nur dann kann er gleich nach dem Speichern melden.
     */
    fun rearms(existing: AlarmEntity?, edited: AlarmEntity): Boolean =
        existing == null || conditionChanged(existing, edited) || reenables(existing, edited)

    /** Ausgelöster einmaliger Alarm (aus, mit letzter Meldung) wird auf «Jedes Mal» gestellt. */
    fun reenables(existing: AlarmEntity, edited: AlarmEntity): Boolean =
        edited.repeating && !existing.repeating && !existing.enabled && existing.lastTriggeredAt > 0

    /**
     * Gespeicherter Alarm: [edited] = Eingaben mit dem Zustand «neu scharf» (so wie bisher immer
     * gespeichert). Hat sich die Bedingung nicht geändert, behält er Ein/Aus, letzte Meldung,
     * Bezugskurs und Scharf/Gemeldet von [existing] — nur Ton, Vibration, Ansage und
     * Wiederholung ändern sich.
     *
     * Ausnahme, bewusst: Ein ausgelöster einmaliger Alarm (aus, mit letzter Meldung) wird mit
     * «Jedes Mal» wieder eingeschaltet und scharf gestellt — wie der Schalter in der Liste
     * (WatchDao.rearmAlarm: `referenceAt` = 0 bei Kursmarken, Hoch/Tief, Funding, Open Interest;
     * Bezugskurs bleibt). Umgekehrt («Jedes Mal» → «Einmal») bleibt der Zustand.
     */
    fun merged(existing: AlarmEntity?, edited: AlarmEntity): AlarmEntity {
        if (existing == null || conditionChanged(existing, edited)) return edited
        val kept = edited.copy(
            enabled = existing.enabled,
            referencePrice = existing.referencePrice,
            lastTriggeredAt = existing.lastTriggeredAt,
            lastTriggeredPrice = existing.lastTriggeredPrice,
            referenceAt = existing.referenceAt,
            // Fenster ohne Bedeutung für die Bedingung: gespeicherten Wert behalten
            windowHours = if (usesWindow(edited.condition)) edited.windowHours else existing.windowHours,
        )
        if (!reenables(existing, edited)) return kept
        return kept.copy(
            enabled = true,
            referenceAt = if (rearmsOnEnable(edited.condition)) 0L else kept.referenceAt,
        )
    }

    /** Gehört das Zeitfenster zur Bedingung? */
    fun usesWindow(condition: AlarmCondition): Boolean =
        condition == AlarmCondition.MOVE_PERCENT_WINDOW || condition.isNearExtreme || condition.isOpenInterest

    /** Bedingungen, die beim Einschalten wieder scharf werden (wie WatchDao.rearmAlarm). */
    private fun rearmsOnEnable(condition: AlarmCondition): Boolean =
        condition.isPriceThreshold || condition.isNearExtreme || condition.isDerivatives

    private fun normalizedCurrency(code: String?): String? =
        code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /** Gleicher Schwellwert — mit kleiner Toleranz, falls das Eingabefeld ihn minimal anders zurückliest. */
    private fun sameThreshold(a: Double, b: Double): Boolean =
        a == b || abs(a - b) <= 1e-9 * max(abs(a), abs(b))
}
