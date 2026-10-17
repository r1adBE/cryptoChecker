package com.cryptochecker.app.domain.alarm

import java.util.Locale

/**
 * Bestätigung nach dem Speichern eines Alarms (Banner unten): «Alarm gespeichert: BTC Kurs über
 * 70’000 USDT · einmalig», bei ausgeschalteten Mitteilungen dazu « · Mitteilungen aus» (mit Aktion
 * «Einstellungen»). Reines Kotlin, testbar — wie `AlarmSavedText` (iOS, AlarmLogic.swift).
 */
object AlarmSavedText {

    /**
     * Teil hinter «Alarm gespeichert: »: Bedingung, Häufigkeit (erster Buchstabe klein, mitten im
     * Satz: «Einmalig» → «einmalig») und ggf. der Hinweis auf ausgeschaltete Mitteilungen.
     * @param condition Bedingung, z. B. «BTC Kurs über 70’000 USDT» oder «Portfolio über 50’000.00 CHF»
     * @param frequency «Einmalig» bzw. «Wiederholend» (vorhandene Texte der Alarmliste)
     * @param notificationsOff «Mitteilungen aus»; null = Mitteilungen erlaubt
     */
    fun detail(condition: String, frequency: String, notificationsOff: String?, locale: Locale): String =
        listOfNotNull(
            condition.trim(),
            frequency.trim().replaceFirstChar { it.lowercase(locale) },
            notificationsOff?.trim(),
        ).filter { it.isNotEmpty() }.joinToString(SEPARATOR)

    /** Bedingung eines Paar-Alarms: Basis-Asset vor dem Titel der Alarmkarte («BTC» + «Kurs über 70’000 USDT»). */
    fun pairCondition(symbol: String, title: String): String =
        listOf(symbol.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    const val SEPARATOR = " · "
}
