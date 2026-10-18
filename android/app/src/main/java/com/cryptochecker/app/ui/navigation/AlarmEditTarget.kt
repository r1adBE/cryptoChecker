package com.cryptochecker.app.ui.navigation

/**
 * Ziel «Anpassen» aus einer Alarm-Mitteilung: «alarm/<Paar>/<Alarm>» — öffnet die Alarme des Paars mit dem Editor
 * dieses Alarms (wie iOS `AppRouter.open("alarm/…")`). Reine Logik, getestet in AlarmEditTargetTest.
 */
object AlarmEditTarget {
    private const val PREFIX = "alarm/"

    fun of(watchId: Long, alarmId: Long): String = "$PREFIX$watchId/$alarmId"

    /** Paar und Alarm aus [target]; null bei anderen oder kaputten Zielen. */
    fun parse(target: String?): Pair<Long, Long>? {
        if (target == null || !target.startsWith(PREFIX)) return null
        val parts = target.removePrefix(PREFIX).split('/')
        if (parts.size != 2) return null
        val watchId = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val alarmId = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return watchId to alarmId
    }
}
