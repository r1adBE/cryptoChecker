package com.cryptochecker.app.settings

/**
 * Kurzwerte für die Zeilen, die in den Einstellungen zu einer Unterseite führen
 * («Markt-Meldungen · 2 aktiv», «Sprachausgabe · Aus»). Reine Logik, wie iOS `SettingsSummary`.
 */
object SettingsSummary {

    /** Themen der Markt-Meldungen, die gezählt werden. */
    const val MARKET_ALERT_TOPICS = 5

    /**
     * Wie viele Themen der Markt-Meldungen eingeschaltet sind: Fear & Greed
     * (unter oder über einer Grenze), Gas (ETH oder BTC), ungewöhnliche Aktivität,
     * Wirtschaftstermine, eigene Grenzen für Bitcoin-Kennzahlen. 0 = alles aus.
     */
    fun marketAlertCount(
        fearGreedBelow: Int,
        fearGreedAbove: Int,
        gasEthTenths: Int,
        gasBtc: Int,
        activity: Boolean,
        macro: Boolean,
        indicators: Boolean = false,
    ): Int = listOf(
        fearGreedBelow > 0 || fearGreedAbove > 0,
        gasEthTenths > 0 || gasBtc > 0,
        activity,
        macro,
        indicators,
    ).count { it }

    /** Zustand der Sprachausgabe als Kurzwert. */
    enum class Speech { OFF, ON, ALARMS_ONLY }

    fun speech(enabled: Boolean, alarmsOnly: Boolean): Speech = when {
        !enabled -> Speech.OFF
        alarmsOnly -> Speech.ALARMS_ONLY
        else -> Speech.ON
    }

    /** Kurzwert der Zeile «Portfolio»: Die Sperre zählt nur bei eingeschaltetem Portfolio. */
    enum class Portfolio { OFF, ON, LOCKED }

    fun portfolio(enabled: Boolean, lock: Boolean): Portfolio = when {
        !enabled -> Portfolio.OFF
        lock -> Portfolio.LOCKED
        else -> Portfolio.ON
    }

    /** Teile des Kurzwerts der Zeile «Merkliste» (Mini-Chart, ≈ Umrechnung); leer = nur Kurs. */
    enum class WatchlistPart { NAMES, SPARKLINE, CONVERTED }

    fun watchlist(sparkline: Boolean, converted: Boolean, names: Boolean = false): List<WatchlistPart> = buildList {
        if (names) add(WatchlistPart.NAMES)
        if (sparkline) add(WatchlistPart.SPARKLINE)
        if (converted) add(WatchlistPart.CONVERTED)
    }
}
