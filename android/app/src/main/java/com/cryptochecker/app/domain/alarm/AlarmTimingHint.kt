package com.cryptochecker.app.domain.alarm

/**
 * Ehrlicher Hinweis «Wann Alarme geprüft werden» als EIN Satz je Plattform (Build 55, Review 14 —
 * vorher vier verschieden lange Fassungen): Android prüft bei geschlossener App unregelmässig, mit
 * «Pünktlich prüfen» etwa alle 15 Minuten (`alarm_timing_hint_android`). Derselbe Satz steht unter
 * jedem Alarm, ist der ganze Text der Bestätigung nach dem ersten Alarm und erscheint einmal nach dem
 * Speichern. Reines Kotlin, testbar — wie `AlarmTimingHint` (iOS, AlarmLogic.swift).
 */
object AlarmTimingHint {

    /**
     * Höchstlänge des Satzes in jeder Sprache: Er ist der ganze Text der Bestätigung nach dem ersten
     * Alarm und muss dort ohne Scrollen lesbar bleiben.
     */
    const val MAX_CHARS = 160

    /**
     * Jetzt zeigen? Nur einmal ([alreadyShown]) und nicht zusätzlich zur Bestätigung des allerersten
     * Alarms ([firstAlarmDialog]) — die zeigt denselben Satz; zwei Dialoge nacheinander wären zu viel.
     * Nach jedem Speichern gilt der Hinweis als gezeigt (Hinweis oder Bestätigung erschien).
     */
    fun shouldShow(alreadyShown: Boolean, firstAlarmDialog: Boolean): Boolean = !alreadyShown && !firstAlarmDialog

    /** Trenner im Pfad «Einstellungen › Kurs-Aktualisierung» (aus den Texten der App). */
    const val PATH_SEPARATOR = " › "

    /** Pfad zum Schalter aus den übersetzten Namen der Ebenen. */
    fun settingsPath(vararg parts: String): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(PATH_SEPARATOR)
}
