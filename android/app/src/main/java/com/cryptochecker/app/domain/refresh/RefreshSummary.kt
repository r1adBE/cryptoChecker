package com.cryptochecker.app.domain.refresh

data class RefreshSummary(
    val checked: Int = 0,
    val failed: Int = 0,
    val alarmsTriggered: Int = 0,

    /** Wie lange der Durchlauf gedauert hat. */
    val durationMillis: Long = 0,
) {
    val hasFailures: Boolean get() = failed > 0
}
