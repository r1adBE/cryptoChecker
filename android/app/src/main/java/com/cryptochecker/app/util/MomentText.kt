package com.cryptochecker.app.util

import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Zeitpunkt eines vergangenen Ereignisses (z. B. «zuletzt ausgelöst» auf der Alarmkarte): heute nur die Uhrzeit mit
 * Sekunden wie [PriceFormat.time] («14:02:11»), an einem anderen Tag Datum und Uhrzeit kurz («12.10.26, 14:02») —
 * vorher stand nur die Uhrzeit da, auch Tage später. Wie `MomentText.swift` (iOS).
 */
object MomentText {

    /** Liegen [millis] und [now] am selben Kalendertag in [zone]? */
    fun isSameDay(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    /**
     * Heute: Uhrzeit mit Sekunden; sonst Datum und Uhrzeit kurz; nicht gesetzt (≤ 0): «—». 12/24 h nach der
     * Systemeinstellung ([ClockText]); [is24] = null: wie die Sprache.
     */
    fun moment(
        millis: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = LocaleNumbers.appLocale,
        is24: Boolean? = ClockText.systemIs24Hour(),
    ): String {
        if (millis <= 0) return "—"
        val format = if (isSameDay(millis, now, zone)) {
            ClockText.format(locale, withSeconds = true, is24 = is24)
        } else {
            ClockText.format(locale, withSeconds = false, is24 = is24, dateStyle = DateFormat.SHORT)
        }
        format.timeZone = TimeZone.getTimeZone(zone)
        return format.format(Date(millis))
    }
}
