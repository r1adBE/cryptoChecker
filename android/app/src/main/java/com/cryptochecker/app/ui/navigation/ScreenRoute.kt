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

    /** «Alle Alarme»; [portfolioAlarmId] > 0: dieser Portfolio-Alarm hervorgehoben (Tipp auf seine Mitteilung). */
    @Serializable
    data class AlarmsOverview(val portfolioAlarmId: Long = 0L) : ScreenRoute

    /** Alarm-Protokoll: aus «Alle Alarme» und neben «Letzte Prüfungen». */
    @Serializable
    data object AlarmLog : ScreenRoute

    /** «Damit Alarme ankommen»: aus «Alle Alarme», dem Hinweis im Alarm-Editor und Einstellungen › Alarme. */
    @Serializable
    data object AlarmDelivery : ScreenRoute

    /**
     * Unterseiten «Einordnung» und «Daten» des Markt-Tabs, [section] = Name aus `MarketSection`
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

    /**
     * Alarme eines Paars; `AlarmsViewModel` liest [watchId] unter [AlarmsArgWatchId] aus dem SavedStateHandle.
     * [editAlarmId] > 0: «Anpassen» aus der Alarm-Mitteilung — der Editor dieses Alarms öffnet sich einmal.
     */
    @Serializable
    data class Alarms(val watchId: Long, val editAlarmId: Long = 0) : ScreenRoute

    /**
     * Seite eines Paars (Tipp auf eine Zeile, Mitteilung, Widget, «Heute auffällig»). [watchId] steht wie bei [Alarms]
     * unter [AlarmsArgWatchId] im SavedStateHandle: das `AlarmsViewModel` der Seite liest ihn für die Alarme des Paars.
     * [preview]: Coin aus dem Markt-Tab, der nicht in der Merkliste steht (Binance «COIN/USDT», nur im Speicher;
     * [watchId] 0). [askNotifications]: eben aus der Vorschau hinzugefügt — einmal nach der Erlaubnis fragen.
     */
    @Serializable
    data class PairPage(val watchId: Long, val preview: String? = null, val askNotifications: Boolean = false) : ScreenRoute

    /** Optionaler Tab «Portfolio» und die Detailansicht eines Coins. */
    @Serializable
    data object Portfolio : ScreenRoute

    @Serializable
    data class PortfolioCoin(val coin: String) : ScreenRoute

    /** Steuer-Arbeitsunterlage (Portfolio ⋯ › «Steuer-Arbeitsunterlage…»). */
    @Serializable
    data object PortfolioTaxWorksheet : ScreenRoute

    companion object {
        /** Schlüssel von [Alarms.watchId] im SavedStateHandle (= Feldname). */
        const val AlarmsArgWatchId = "watchId"

        /** Schlüssel von [Alarms.editAlarmId] im SavedStateHandle (= Feldname). */
        const val AlarmsArgEditAlarmId = "editAlarmId"

        /** Schlüssel von [PairPage.askNotifications] im SavedStateHandle (= Feldname); nach dem Fragen false. */
        const val PairPageArgAskNotifications = "askNotifications"

        /** Schlüssel von [MarketSubpage.macro] im SavedStateHandle (= Feldname); nach dem Scrollen false. */
        const val MarketSubpageArgMacro = "macro"
    }
}
