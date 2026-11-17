package com.cryptochecker.app.domain.settings

import com.cryptochecker.app.domain.alarm.QuietHours
import java.util.Calendar
import java.util.TimeZone

/**
 * Darstellung «Nach Uhrzeit»: dunkel ab [AppSettings.themeDarkFrom] bis [AppSettings.themeLightFrom], sonst hell — für
 * App und Widgets (Widget-Wahl «System» folgt der App). Zeiten in Minuten seit Mitternacht (0..1439), Ortszeit des
 * Geräts; der Zeitraum darf über Mitternacht gehen (Vorgabe 23:00–07:00). Beginn gehört dazu, Ende nicht; gleiche
 * Zeiten = nie dunkel (wie [QuietHours.isQuiet]). Wie iOS `ThemeSchedule`, gemeinsame Testfälle `theme_schedule.json`.
 */
object ThemeSchedule {
    const val DEFAULT_DARK_FROM = 23 * 60
    const val DEFAULT_LIGHT_FROM = 7 * 60

    fun isDarkAt(darkFrom: Int, lightFrom: Int, minuteOfDay: Int): Boolean =
        QuietHours.isQuiet(true, darkFrom, lightFrom, minuteOfDay)

    /** Hell/Dunkel, das die App erzwingt: null = wie das System. «Nach Uhrzeit» ([scheduled]) geht vor [darkMode]. */
    fun forcedDark(darkMode: Boolean?, scheduled: Boolean, darkFrom: Int, lightFrom: Int, minuteOfDay: Int): Boolean? =
        if (scheduled) isDarkAt(darkFrom, lightFrom, minuteOfDay) else darkMode

    /** Wie [forcedDark] mit den Einstellungen zum Zeitpunkt [millis] in [zone]. */
    fun forcedDark(
        settings: AppSettings,
        millis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Boolean? = forcedDark(
        settings.darkMode,
        settings.themeScheduled,
        settings.themeDarkFrom,
        settings.themeLightFrom,
        QuietHours.minuteOfDay(millis, zone)
    )

    /** Wirksam dunkel: erzwungen ([forcedDark]) oder sonst wie das System ([systemDark]). */
    fun isDark(settings: AppSettings, systemDark: Boolean, millis: Long = System.currentTimeMillis()): Boolean =
        forcedDark(settings, millis) ?: systemDark

    /**
     * Nächster Wechsel nach [now] (ms): die nächste der beiden Zeiten als Ortszeit in [zone]; null ohne «Nach Uhrzeit»,
     * mit ungültigen oder gleichen Zeiten. Fällt eine Zeit in die übersprungene Stunde der Sommerzeit, gilt die Uhrzeit
     * danach (der Wechsel kommt dann früher, nie später).
     */
    fun nextSwitchAt(settings: AppSettings, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (!settings.themeScheduled) return null
        return nextSwitchAt(settings.themeDarkFrom, settings.themeLightFrom, now, zone)
    }

    fun nextSwitchAt(darkFrom: Int, lightFrom: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (!QuietHours.isValidMinute(darkFrom) || !QuietHours.isValidMinute(lightFrom) || darkFrom == lightFrom) return null
        return minOf(nextAt(darkFrom, now, zone), nextAt(lightFrom, now, zone))
    }

    private fun nextAt(minute: Int, now: Long, zone: TimeZone): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, minute / 60)
        calendar.set(Calendar.MINUTE, minute % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) {
            calendar.timeInMillis = now
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, minute / 60)
            calendar.set(Calendar.MINUTE, minute % 60)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
