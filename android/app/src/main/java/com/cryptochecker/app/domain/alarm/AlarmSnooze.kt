package com.cryptochecker.app.domain.alarm

/**
 * «1 h später» aus der Alarm-Mitteilung: Der Alarm bleibt scharf, nur seine nächste Meldung wartet bis
 * [snoozeUntil] — wie die Nachtruhe im Melde-Schritt ([AlarmDelivery]), nie in der Prüfung selbst
 * ([AlarmEvaluator] bleibt unverändert). Ist die Bedingung danach noch erfüllt, meldet er sich beim nächsten
 * Durchlauf. Reine Logik — wie `AlarmSnooze.swift` (gemeinsamer Testfall `testdata/parity/alarm_snooze.json`).
 */
object AlarmSnooze {

    /** Dauer von «1 h später». */
    const val SNOOZE_MILLIS = 60L * 60_000

    /** Ende der Pause, wenn jetzt ([now]) «1 h später» getippt wird. */
    fun snoozeUntil(now: Long): Long = now + SNOOZE_MILLIS

    /**
     * Hält die Pause die Meldung jetzt zurück? Nur zwischen jetzt und [until]; liegt [until] weiter als
     * [SNOOZE_MILLIS] in der Zukunft (Uhr zurückgestellt), gilt die Pause als vorbei — ein Alarm soll nie
     * länger als die versprochene Stunde schweigen.
     */
    fun isSnoozed(until: Long?, now: Long): Boolean {
        if (until == null || until <= 0) return false
        val rest = until - now
        return rest > 0 && rest <= SNOOZE_MILLIS
    }

    /** Nur noch laufende Pausen (abgelaufene fallen beim Speichern weg). */
    fun prune(all: Map<Long, Long>, now: Long): Map<Long, Long> = all.filter { (_, until) -> isSnoozed(until, now) }
}
