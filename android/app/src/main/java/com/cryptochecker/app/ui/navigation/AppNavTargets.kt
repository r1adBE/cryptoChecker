// Sprünge im Navigationsgraphen (Teil von `AppNavHost`): Ziel einer App-Verknüpfung, Mitteilung oder Widget-Zeile,
// Wechsel des Tabs und die Seite «Paar hinzufügen».
// Eigene Datei, weil `AppNavHost` sonst zu lang würde.
package com.cryptochecker.app.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.ui.features.info.MarketJump
import com.cryptochecker.app.ui.features.settings.SettingsPage

/**
 * App-Verknüpfung, Mitteilung bzw. Widget ([openTarget], siehe `MainActivity`): direkt zum gewünschten Bereich springen.
 * Sprung in den Markt-Tab, «Was bewegt sich mit?» und Aktionsblatt eines Paars meldet sie über [onMarketJump],
 * [onWhyWatch] bzw. [onFocusWatch] an den Zustand von [AppNavHost].
 */
internal fun openAppTarget(
    navigation: NavHostController,
    openTarget: String,
    portfolioEnabled: Boolean,
    onMarketJump: (String) -> Unit,
    onWhyWatch: (Long) -> Unit,
    onFocusWatch: (Long) -> Unit,
) {
    when (openTarget) {
        // «Paar hinzufügen» liegt über der Merkliste: zurück führt dorthin
        "add" -> {
            navigation.navigateToTab(ScreenRoute.Watchlist)
            navigation.openExplorer()
        }
        "cycle" -> navigation.navigateToTab(ScreenRoute.MarketPhase)
        // Mitteilung «Wirtschaftstermine»: Markt-Tab, Register mit dem Hinweis (dort gescrollt)
        com.cryptochecker.app.ui.MainActivity.OPEN_MARKET_MACRO -> {
            onMarketJump(MarketJump.MACRO.name)
            navigation.navigateToTab(ScreenRoute.MarketPhase)
            // Eine gemerkte Unterseite schliessen: der Tab entscheidet, wohin es geht
            navigation.popBackStack<ScreenRoute.MarketPhase>(inclusive = false)
        }
        "alarms" -> navigation.navigate(ScreenRoute.AlarmsOverview) { launchSingleTop = true }
        // Portfolio-Widget: Portfolio-Tab; ist es aus, Einstellungen › Portfolio (dort der Schalter)
        "portfolio" -> when (PortfolioOff.widgetTarget(portfolioEnabled)) {
            PortfolioOff.WidgetTarget.PORTFOLIO_TAB -> navigation.navigateToTab(ScreenRoute.Portfolio)
            PortfolioOff.WidgetTarget.PORTFOLIO_SETTINGS -> {
                navigation.navigateToTab(ScreenRoute.Settings)
                // Eine gemerkte Unterseite schliessen, dann die Seite «Portfolio» darüber
                navigation.popBackStack<ScreenRoute.Settings>(inclusive = false)
                navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.PORTFOLIO.name)) { launchSingleTop = true }
            }
        }
        else -> com.cryptochecker.app.ui.MainActivity.whyWatchId(openTarget)?.let { id ->
            onWhyWatch(id)
            navigation.navigateToTab(ScreenRoute.Watchlist)
        } ?: AlarmEditTarget.parse(openTarget)?.let { (watchId, alarmId) ->
            // «Anpassen» aus der Alarm-Mitteilung: Merkliste, darüber die Alarme des Paars mit dem Editor des Alarms
            navigation.navigateToTab(ScreenRoute.Watchlist)
            navigation.popBackStack<ScreenRoute.Watchlist>(inclusive = false)
            navigation.navigate(ScreenRoute.Alarms(watchId, editAlarmId = alarmId))
        } ?: com.cryptochecker.app.ui.MainActivity.watchTargetId(openTarget)?.let { id ->
            onFocusWatch(id)
            navigation.navigateToTab(ScreenRoute.Watchlist)
            // Eine offene Unterseite über der Merkliste (z. B. «Alarme») schliessen
            navigation.popBackStack<ScreenRoute.Watchlist>(inclusive = false)
        }
    }
}

/** Seite «Paar hinzufügen» über dem aktuellen Tab (eigene Seite mit Zurück, ohne Tableiste). */
internal fun NavHostController.openExplorer() {
    navigate(ScreenRoute.Explorer) { launchSingleTop = true }
}

internal fun NavHostController.navigateToTab(route: ScreenRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
