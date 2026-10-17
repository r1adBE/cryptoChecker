package com.cryptochecker.app.ui.navigation

object ScreenRoute {
    const val Watchlist = "watchlist"
    const val Explorer = "explorer"
    const val Settings = "settings"
    const val MarketPhase = "market_phase"
    const val AlarmsOverview = "alarms_overview"

    /** G-10: Unterseiten «Einordnung» und «Daten» des Markt-Tabs, Name aus `MarketSection`. */
    const val MarketSectionArg = "section"
    const val MarketSectionRoute = "market/section/{$MarketSectionArg}"

    fun marketSection(name: String) = "market/section/$name"

    /** Unterseiten der Einstellungen (Runde 13b). */
    const val SettingsMarketAlerts = "settings/market_alerts"
    const val SettingsSpeech = "settings/speech"

    /** Runde 23f: übrige Unterseiten der Einstellungen, Name aus `SettingsPage`. */
    const val SettingsPageArg = "page"
    const val SettingsPageRoute = "settings/page/{$SettingsPageArg}"

    fun settingsPage(name: String) = "settings/page/$name"

    const val AlarmsArgWatchId = "watchId"
    const val Alarms = "alarms/{$AlarmsArgWatchId}"

    fun alarms(watchId: Long) = "alarms/$watchId"

    /** Optionaler Tab «Portfolio» und die Detailansicht eines Coins. */
    const val Portfolio = "portfolio"
    const val PortfolioArgCoin = "coin"
    const val PortfolioCoin = "portfolio/coin/{$PortfolioArgCoin}"

    fun portfolioCoin(coin: String) = "portfolio/coin/${android.net.Uri.encode(coin)}"
}
