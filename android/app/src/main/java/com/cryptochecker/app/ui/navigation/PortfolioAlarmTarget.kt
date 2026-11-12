package com.cryptochecker.app.ui.navigation

/**
 * Ziel der Mitteilung eines Portfolio-Alarms: «portfolioAlarm/<Alarm>» — öffnet «Alle Alarme» mit dem ausgelösten
 * Portfolio-Alarm hervorgehoben (wie iOS `PortfolioAlarmTarget`). Die Mitteilung selbst bleibt neutral (kein Betrag);
 * nur die Kennung steht im Ziel. Reine Logik, getestet in PortfolioAlarmTargetTest.
 */
object PortfolioAlarmTarget {
    private const val PREFIX = "portfolioAlarm/"

    fun of(alarmId: Long): String = "$PREFIX$alarmId"

    /** Alarm aus [target]; null bei anderen oder kaputten Zielen. */
    fun parse(target: String?): Long? =
        target?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()?.takeIf { it > 0 }
}
