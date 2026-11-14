package com.cryptochecker.app.domain.refresh

data class RefreshSummary(
    val checked: Int = 0,
    val failed: Int = 0,
    val alarmsTriggered: Int = 0,

    /** Wie lange der Durchlauf gedauert hat. */
    val durationMillis: Long = 0,
    /** Paare pausierter Börsen: nicht angefragt, ihre Alarme nicht geprüft ([CheckRunLog.result]). */
    val paused: Int = 0,
    /** Paare, die am Zeitbudget der Prüfung scheiterten ([CheckBudget]); dann keine volle Prüfung. */
    val cutByBudget: Int = 0,
)
