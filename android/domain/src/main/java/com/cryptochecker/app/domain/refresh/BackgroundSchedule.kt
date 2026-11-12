package com.cryptochecker.app.domain.refresh

/**
 * Entscheide der Hintergrund-Prüfung: «Pünktlich prüfen» (Android: exakter Wecker im gewählten
 * Intervall der Hintergrund-Aktualisierung, mindestens 15 Min.), Sperre gegen doppelte Läufe, Akku-Hinweis. Reine Logik ohne Android — wie
 * `BackgroundSchedule.swift` (dort nur Sperre und Hinweis genutzt; iOS kennt keinen Wecker).
 */
object BackgroundSchedule {

    /** Kürzester Abstand der pünktlichen Prüfung; über dem Doze-Minimum (rund 9 Min.) für allow-while-idle. */
    const val EXACT_INTERVAL_MILLIS = 15 * 60_000L

    /**
     * Abstand der pünktlichen Prüfung = gewähltes Intervall der Hintergrund-Aktualisierung (wie WorkManager), nie unter
     * [EXACT_INTERVAL_MILLIS] — «Sparsam» mit 60 Min. weckt also auch nur stündlich.
     */
    fun exactIntervalMillis(backgroundIntervalMinutes: Int): Long =
        maxOf(backgroundIntervalMinutes.coerceAtLeast(0) * 60_000L, EXACT_INTERVAL_MILLIS)

    /** Lief eine Prüfung mit mindestens einem Kurs vor weniger als so langer Zeit, entfällt die nächste. */
    const val DEDUPE_WINDOW_MILLIS = 5 * 60_000L

    /** Toleranz des Sperrfensters unter dem Wecker ([dedupeWindowMillis]): Dauer eines Laufs samt Spielraum. */
    const val DEDUPE_TOLERANCE_MILLIS = 2 * 60_000L

    /** Zustand des Schalters «Pünktlich prüfen». */
    enum class ExactState {
        /** Ausgeschaltet (Standard): nur WorkManager. */
        OFF,

        /** Wecker gestellt (WorkManager bleibt als Netz darunter). */
        ACTIVE,

        /** Eingeschaltet, aber «Wecker und Erinnerungen» nicht erlaubt: WorkManager allein. */
        PERMISSION_MISSING,

        /** Eingeschaltet, aber nichts zu prüfen (keine aktiven Paare, kein Portfolio-, Gas- oder Depeg-Alarm): kein Wecker. */
        NOTHING_TO_CHECK,
    }

    fun exactState(enabled: Boolean, permitted: Boolean, hasWork: Boolean): ExactState = when {
        !enabled -> ExactState.OFF
        !permitted -> ExactState.PERMISSION_MISSING
        !hasWork -> ExactState.NOTHING_TO_CHECK
        else -> ExactState.ACTIVE
    }

    /** Wecker stellen? Nur eingeschaltet, erlaubt und wenn es etwas zu prüfen gibt. */
    fun shouldArmExact(enabled: Boolean, permitted: Boolean, hasWork: Boolean): Boolean =
        exactState(enabled, permitted, hasWork) == ExactState.ACTIVE

    /** Nächster Weckzeitpunkt nach einem Lauf bzw. beim Einschalten ([intervalMillis] aus [exactIntervalMillis]). */
    fun nextExactAt(now: Long, intervalMillis: Long = EXACT_INTERVAL_MILLIS): Long = now + intervalMillis

    /**
     * Wecker neu stellen? Ja bei [force] (Neustart, Update, neue Berechtigung — Android hat die Wecker
     * gelöscht), wenn keiner gestellt ist ([armedFor] ≤ 0), er abgelaufen ist oder unplausibel weit
     * in der Zukunft liegt (Uhr verstellt). Sonst nicht: Jede Änderung der Einstellungen würde den
     * Takt sonst immer weiter hinausschieben.
     */
    fun needsArm(armedFor: Long, now: Long, force: Boolean = false, intervalMillis: Long = EXACT_INTERVAL_MILLIS): Boolean =
        force || armedFor <= now || armedFor > now + intervalMillis

    /**
     * Prüfung überspringen? Nur die vom System geplanten Läufe (WorkManager, Wecker, iOS-Aufgabe), wenn
     * die letzte Prüfung mit mindestens einem Kurs keine [DEDUPE_WINDOW_MILLIS] her ist. Widget-Knopf
     * und App laufen immer. Zeitpunkt in der Zukunft (Uhr verstellt): nicht überspringen.
     */
    fun shouldSkip(
        trigger: CheckTrigger,
        lastFinishedAt: Long,
        now: Long,
        windowMillis: Long = DEDUPE_WINDOW_MILLIS,
    ): Boolean {
        if (!trigger.scheduled || lastFinishedAt <= 0) return false
        val age = now - lastFinishedAt
        return age in 0 until windowMillis
    }

    /**
     * Sperrfenster für [shouldSkip]. Ist «Pünktlich prüfen» gestellt, gibt der Wecker den Takt vor und WorkManager ist nur
     * das Netz darunter: ein WorkManager-Lauf entfällt, solange die letzte volle Prüfung kein Intervall her ist — er
     * läuft also nur, wenn der Wecker ausbleibt (Hersteller-Akkusparen, Berechtigung entzogen, offline). WorkManager
     * bleibt dafür geplant (robuster als ihn abzusagen: fällt der Wecker still weg, prüft sonst niemand mehr).
     * Wecker selbst, iOS-Aufgabe und alles ohne Wecker: [DEDUPE_WINDOW_MILLIS].
     *
     * Das Fenster ist um [DEDUPE_TOLERANCE_MILLIS] kürzer als das Intervall (nie unter [DEDUPE_WINDOW_MILLIS]): Die letzte
     * volle Prüfung wird am ENDE eines Laufs vermerkt, der nächste WorkManager-Lauf beginnt aber ein Intervall nach dem
     * BEGINN des vorigen — ihr Alter läge sonst immer knapp unter dem Intervall, und bliebe der Wecker aus, fiele jeder
     * zweite Lauf weg (Prüfung nur alle zwei Intervalle).
     */
    fun dedupeWindowMillis(trigger: CheckTrigger, exactArmed: Boolean, intervalMillis: Long): Long =
        if (trigger == CheckTrigger.WORKER && exactArmed) {
            maxOf(intervalMillis - DEDUPE_TOLERANCE_MILLIS, DEDUPE_WINDOW_MILLIS)
        } else {
            DEDUPE_WINDOW_MILLIS
        }

    /**
     * Volle Prüfung für [shouldSkip]? Alle abfragbaren Paare ([attempted]; Paare pausierter Börsen
     * fragt auch der nächste Lauf nicht ab) versucht und mehr als die Hälfte mit Kurs. Live-Takte
     * und Teil-Läufe zählen nicht — sie setzen nur die Uhrzeit im Widget.
     *
     * Nicht gehandelte Paare und Paare stillgelegter Börsen ([notTraded]) zählen weder als versucht noch als gescheitert:
     * Sie haben keinen Kurs mehr, ihre Alarme ruhen. Sind ALLE Paare so ([attempted] 0, [notTraded] > 0), ist der Lauf
     * trotzdem voll — Portfolio-, Gas- und Depeg-Alarme wurden geprüft.
     */
    fun isFullCheck(attempted: Int, failed: Int, notTraded: Int = 0): Boolean =
        if (attempted == 0 && failed == 0) {
            notTraded > 0
        } else {
            attempted > 0 && failed in 0 until attempted && (attempted - failed) * 2 > attempted
        }

    /**
     * Zählt ein Lauf als Aktualisierung (Uhrzeit im Widget-Kopf, iOS `markFullRefresh`)? Mindestens ein Kurs kam
     * ([failed] < [attempted]) — oder alle Paare sind nicht gehandelt bzw. stillgelegt ([notTraded], kein Abruf nötig).
     */
    fun refreshed(attempted: Int, failed: Int, notTraded: Int = 0): Boolean =
        failed < attempted || (attempted == 0 && failed == 0 && notTraded > 0)

    /**
     * Zählt ein Lauf als «Prüfung mit allen Alarmen» ([isFullCheck] → `lastFullCheck`, iOS `lastAlarmCheckAt`)? Nur eine
     * volle Prüfung ([fullCheck]), und warten Alarme mit Zusatzdaten auf einen späteren Schritt ([deferredWaiting] — nur
     * die iOS-Hintergrund-Aufgabe; Android wertet alle Alarmarten in einem Schritt aus), erst wenn auch sie geprüft sind
     * ([deferredChecked]). Sonst bleibt der alte Wert. Gespiegelt in `BackgroundSchedule.swift`.
     */
    fun marksAlarmCheck(fullCheck: Boolean, deferredWaiting: Boolean = false, deferredChecked: Boolean = false): Boolean =
        fullCheck && (!deferredWaiting || deferredChecked)

    /** Akku-Hinweis zeigen? Nur eingeschränkt und wenn überhaupt im Hintergrund geprüft wird (Paare oder Alarme da). */
    fun showBatteryHint(batteryRestricted: Boolean, hasWork: Boolean): Boolean = batteryRestricted && hasWork
}
