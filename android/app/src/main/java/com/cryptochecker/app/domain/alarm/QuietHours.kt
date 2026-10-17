package com.cryptochecker.app.domain.alarm

/**
 * Nachtruhe: In diesem Zeitraum lösen Paar- und Portfolio-Alarme NICHT aus, sondern warten
 * bis zum Ende ([holdsAlarms]). Übrige Meldungen (Aktivität, Gebühren, Termine) kommen
 * lautlos (eigener Kanal ohne Ton und Vibration), Sprachausgabe schweigt.
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

    /**
     * Eine Regel für Paar- und Portfolio-Alarme (beide Plattformen gleich): In der Nachtruhe
     * wird nicht ausgelöst — der Alarm bleibt scharf und unverändert (nichts gespeichert, keine
     * Abklingzeit, einmalige bleiben eingeschaltet). Nach dem Ende der Nachtruhe wertet die
     * nächste Prüfung ihn wieder aus; gilt die Bedingung dann noch, meldet er sich ganz normal
     * mit Ton — eine Meldung je Alarm, kein Nachholen gesammelter Auslösungen.
     * Bewusst in Kauf genommen: Was nachts eintrat und wieder verging (Marke hin und zurück
     * gekreuzt, Volumen-Spitze einer vergangenen Stunde), meldet sich danach nicht mehr.
     * Wieder scharf stellen und Bezugskurse setzen läuft auch nachts weiter (kein Auslösen).
     */
    fun holdsAlarms(enabled: Boolean, start: Int, end: Int, minuteOfDay: Int): Boolean =
        isQuiet(enabled, start, end, minuteOfDay)

    /**
     * Darf ein fälliger Paar- oder Portfolio-Alarm jetzt auslösen (speichern und melden)? Nicht in
     * der Nachtruhe ([holdAlarms], siehe [holdsAlarms]) und nicht ohne zustellbare Meldung
     * ([deliverable]: Erlaubnis da, Mitteilungen an, Kanal nicht stumm). Gilt für einmalige UND
     * wiederholende Alarme: Der Alarm bleibt scharf und unverändert (keine Abklingzeit, Kreuzung
     * nicht verbraucht) und meldet sich, sobald die Meldung wieder ankommt.
     */
    fun mayFire(holdAlarms: Boolean, deliverable: Boolean): Boolean = !holdAlarms && deliverable

    /** Minute des Tages in der Ortszeit des Geräts. */
    fun minuteOfDay(millis: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val calendar = java.util.Calendar.getInstance(zone)
        calendar.timeInMillis = millis
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }
}
