package com.cryptochecker.app.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Ziele der Navigation, typsicher (Navigation Compose mit @Serializable): Argumente stehen als
 * Felder in der Klasse statt in einem Text wie «alarms/{watchId}». Navigation legt sie unter dem
 * Feldnamen in den SavedStateHandle des Eintrags; sie überstehen so auch einen Prozess-Neustart.
 * Sprünge von aussen (Mitteilung, Widget, App-Verknüpfung) kommen weiter als Text über
 * `MainActivity` (openTarget) und werden in `AppNavHost` auf diese Ziele abgebildet.
 */
sealed interface ScreenRoute {
    @Serializable
    data object Watchlist : ScreenRoute

    @Serializable
    data object Explorer : ScreenRoute

    @Serializable
    data object Settings : ScreenRoute

    @Serializable
    data object MarketPhase : ScreenRoute

    @Serializable
    data object AlarmsOverview : ScreenRoute

    /** Alarm-Protokoll (Build 80): aus «Alle Alarme» und neben «Letzte Prüfungen». */
    @Serializable
    data object AlarmLog : ScreenRoute

    /**
     * G-10: Unterseiten «Einordnung» und «Daten» des Markt-Tabs, [section] = Name aus `MarketSection`
     * (unbekannte Namen fängt `MarketSections.subpage` ab). [macro]: Mitteilung «Wirtschaftstermine» —
     * die Seite «Daten» scrollt einmal zum Wirtschaftsdaten-Hinweis.
     */
    @Serializable
    data class MarketSubpage(val section: String, val macro: Boolean = false) : ScreenRoute

    /** Unterseiten der Einstellungen mit eigener Route. */
    @Serializable
    data object SettingsMarketAlerts : ScreenRoute

    @Serializable
    data object SettingsSpeech : ScreenRoute

    /** Übrige Unterseiten der Einstellungen, [page] = Name aus `SettingsPage` (unbekannt: `SettingsPage.fromName`). */
    @Serializable
    data class SettingsSubpage(val page: String) : ScreenRoute

    /** Alarme eines Paars; `AlarmsViewModel` liest [watchId] unter [AlarmsArgWatchId] aus dem SavedStateHandle. */
    @Serializable
    data class Alarms(val watchId: Long) : ScreenRoute

    /** Optionaler Tab «Portfolio» und die Detailansicht eines Coins. */
    @Serializable
    data object Portfolio : ScreenRoute

    @Serializable
    data class PortfolioCoin(val coin: String) : ScreenRoute

    companion object {
        /** Schlüssel von [Alarms.watchId] im SavedStateHandle (= Feldname). */
        const val AlarmsArgWatchId = "watchId"

        /** Schlüssel von [MarketSubpage.macro] im SavedStateHandle (= Feldname); nach dem Scrollen false. */
        const val MarketSubpageArgMacro = "macro"
    }
}
