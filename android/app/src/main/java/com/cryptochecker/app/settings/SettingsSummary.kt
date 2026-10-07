package com.cryptochecker.app.settings

/**
 * Kurzwerte für die Zeilen, die in den Einstellungen zu einer Unterseite führen
 * («Markt-Meldungen · 2 aktiv», «Sprachausgabe · Aus»). Reine Logik, wie iOS `SettingsSummary`.
 */
object SettingsSummary {

    /** Themen der Markt-Meldungen, die gezählt werden. */
    const val MARKET_ALERT_TOPICS = 5

    /**
     * Wie viele Themen der Markt-Meldungen eingeschaltet sind: Marktphase, Fear & Greed
     * (unter oder über einer Grenze), Gas (ETH oder BTC), ungewöhnliche Aktivität,
     * Wirtschaftstermine. 0 = alles aus.
     */
    fun marketAlertCount(
        zone: Boolean,
        fearGreedBelow: Int,
        fearGreedAbove: Int,
        gasEthTenths: Int,
        gasBtc: Int,
        activity: Boolean,
        macro: Boolean,
    ): Int = listOf(
        zone,
        fearGreedBelow > 0 || fearGreedAbove > 0,
        gasEthTenths > 0 || gasBtc > 0,
        activity,
        macro,
    ).count { it }

    /** Zustand der Sprachausgabe als Kurzwert. */
    enum class Speech { OFF, ON, ALARMS_ONLY }

    fun speech(enabled: Boolean, alarmsOnly: Boolean): Speech = when {
        !enabled -> Speech.OFF
        alarmsOnly -> Speech.ALARMS_ONLY
        else -> Speech.ON
    }
}
