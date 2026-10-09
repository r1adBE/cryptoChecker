package com.cryptochecker.app.domain.alarm

/**
 * Nachtruhe: In diesem Zeitraum kommen Alarme lautlos (eigener Kanal ohne Ton und
 * Vibration, keine Sprachausgabe). Ausgewertet, ausgelöst und gespeichert wird wie
 * sonst auch — nur die Zustellung ist leise.
 *
 * Zeiten in Minuten seit Mitternacht (0..1439), Ortszeit des Geräts im Moment der
 * Meldung. Der Zeitraum darf über Mitternacht gehen (23:00–07:00) oder nicht
 * (13:00–14:00); Beginn == Ende heisst «nie». Beginn gehört dazu, Ende nicht.
 */
object QuietHours {

    const val DEFAULT_START = 23 * 60
    const val DEFAULT_END = 7 * 60
    const val MINUTES_PER_DAY = 24 * 60

    fun isValidMinute(minute: Int): Boolean = minute in 0 until MINUTES_PER_DAY

    fun isQuiet(enabled: Boolean, start: Int, end: Int, minuteOfDay: Int): Boolean {
        if (!enabled) return false
        if (!isValidMinute(start) || !isValidMinute(end)) return false
        if (start == end) return false
        val now = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return if (start < end) now in start until end
        else now >= start || now < end
    }

    /** Minute des Tages in der Ortszeit des Geräts. */
    fun minuteOfDay(millis: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val calendar = java.util.Calendar.getInstance(zone)
        calendar.timeInMillis = millis
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }
}
