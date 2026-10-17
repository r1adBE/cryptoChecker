package com.cryptochecker.app.domain.refresh

/**
 * Entscheide der Hintergrund-Prüfung (Build 46): «Pünktlich prüfen» (Android: exakter Wecker alle
 * 15 Min.), Sperre gegen doppelte Läufe, Akku-Hinweis. Reine Logik ohne Android — wie
 * `BackgroundSchedule.swift` (dort nur Sperre und Hinweis genutzt; iOS kennt keinen Wecker).
 */
object BackgroundSchedule {

    /** Abstand der pünktlichen Prüfung; über dem Doze-Minimum (rund 9 Min.) für allow-while-idle. */
    const val EXACT_INTERVAL_MILLIS = 15 * 60_000L

    /** Lief eine Prüfung mit mindestens einem Kurs vor weniger als so langer Zeit, entfällt die nächste. */
    const val DEDUPE_WINDOW_MILLIS = 5 * 60_000L

    /** Zustand des Schalters «Pünktlich prüfen». */
    enum class ExactState {
        /** Ausgeschaltet (Standard): nur WorkManager. */
        OFF,

        /** Wecker gestellt (WorkManager bleibt als Netz darunter). */
        ACTIVE,

        /** Eingeschaltet, aber «Wecker und Erinnerungen» nicht erlaubt: WorkManager allein. */
        PERMISSION_MISSING,

        /** Eingeschaltet, aber keine Paare: kein Wecker. */
        NOTHING_TO_CHECK,
    }

    fun exactState(enabled: Boolean, permitted: Boolean, hasWork: Boolean): ExactState = when {
        !enabled -> ExactState.OFF
        !permitted -> ExactState.PERMISSION_MISSING
        !hasWork -> ExactState.NOTHING_TO_CHECK
        else -> ExactState.ACTIVE
    }

    /** Wecker stellen? Nur eingeschaltet, erlaubt und mit Paaren. */
    fun shouldArmExact(enabled: Boolean, permitted: Boolean, hasWork: Boolean): Boolean =
        exactState(enabled, permitted, hasWork) == ExactState.ACTIVE

    /** Nächster Weckzeitpunkt nach einem Lauf bzw. beim Einschalten. */
    fun nextExactAt(now: Long): Long = now + EXACT_INTERVAL_MILLIS

    /**
     * Wecker neu stellen? Ja bei [force] (Neustart, Update, neue Berechtigung — Android hat die Wecker
     * gelöscht), wenn keiner gestellt ist ([armedFor] ≤ 0), er abgelaufen ist oder unplausibel weit
     * in der Zukunft liegt (Uhr verstellt). Sonst nicht: Jede Änderung der Einstellungen würde den
     * Takt sonst immer weiter hinausschieben.
     */
    fun needsArm(armedFor: Long, now: Long, force: Boolean = false): Boolean =
        force || armedFor <= now || armedFor > now + EXACT_INTERVAL_MILLIS

    /**
     * Prüfung überspringen? Nur die vom System geplanten Läufe (WorkManager, Wecker, iOS-Aufgabe), wenn
     * die letzte Prüfung mit mindestens einem Kurs keine [DEDUPE_WINDOW_MILLIS] her ist. Widget-Knopf
     * und App laufen immer. Zeitpunkt in der Zukunft (Uhr verstellt): nicht überspringen.
     */
    fun shouldSkip(trigger: CheckTrigger, lastFinishedAt: Long, now: Long): Boolean {
        if (!trigger.scheduled || lastFinishedAt <= 0) return false
        val age = now - lastFinishedAt
        return age in 0 until DEDUPE_WINDOW_MILLIS
    }

    /**
     * Volle Prüfung für [shouldSkip]? Alle abfragbaren Paare ([attempted]; Paare pausierter Börsen
     * fragt auch der nächste Lauf nicht ab) versucht und mehr als die Hälfte mit Kurs. Live-Takte
     * und Teil-Läufe zählen nicht — sie setzen nur die Uhrzeit im Widget.
     */
    fun isFullCheck(attempted: Int, failed: Int): Boolean =
        attempted > 0 && failed in 0 until attempted && (attempted - failed) * 2 > attempted

    /** Akku-Hinweis zeigen? Nur eingeschränkt und wenn überhaupt im Hintergrund geprüft wird (Paare da). */
    fun showBatteryHint(batteryRestricted: Boolean, hasWork: Boolean): Boolean = batteryRestricted && hasWork
}
