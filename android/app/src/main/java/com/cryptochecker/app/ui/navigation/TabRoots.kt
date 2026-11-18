// Startseiten der Tabs (Merkliste, Markt, Portfolio, Einstellungen) für den Navigationsgraphen und für die linke
// Spalte im breiten Fenster ([TwoPane]). Eigene Datei: Beide Orte zeichnen dieselbe Startseite mit denselben Sprüngen.
package com.cryptochecker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.cryptochecker.app.domain.lock.PortfolioAccess
import com.cryptochecker.app.ui.features.info.MarketJump
import com.cryptochecker.app.ui.features.info.MarketPhaseScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioScreen
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.features.settings.SettingsScreen
import com.cryptochecker.app.ui.features.settings.SettingsSearchTarget
import com.cryptochecker.app.ui.features.watchlist.WatchlistScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel

/**
 * Zustand und Sprünge, die die Startseiten brauchen ([AppNavHost] hält sie). Lese-Funktionen, damit jede Seite den Stand
 * beim Zeichnen frisch liest.
 */
internal class TabRootArgs(
    val navigation: NavHostController,
    val activityOwner: ViewModelStoreOwner,
    val whyWatchId: () -> Long?,
    val onWhyHandled: () -> Unit,
    val focusWatchId: () -> Long?,
    val onFocusHandled: () -> Unit,
    val marketJump: () -> String?,
    val onMarketJumpChange: (String?) -> Unit,
    val onExplorerSearch: (String) -> Unit,
    val portfolioAccess: () -> PortfolioAccess,
    val requestUnlock: () -> Unit,
    val lockViewModel: PortfolioLockViewModel,
    val onSettingsAnchorChange: (String?) -> Unit,
)

/** Startseite des Tabs von [entry] (Merkliste, Markt, Portfolio oder Einstellungen). */
@Composable
internal fun TabRoot(entry: NavBackStackEntry, args: TabRootArgs) {
    val destination = entry.destination
    when {
        destination.hasRoute<ScreenRoute.Watchlist>() -> WatchlistRoot(entry, args)
        destination.hasRoute<ScreenRoute.MarketPhase>() -> MarketRoot(entry, args)
        destination.hasRoute<ScreenRoute.Portfolio>() -> PortfolioRoot(entry, args)
        destination.hasRoute<ScreenRoute.Settings>() -> SettingsRoot(entry, args)
    }
}

/** Ziel einer Startseite im Graphen: breit steht sie links ([TwoPane]), hier dann nur der Platzhalter. */
@Composable
internal fun TabRootSlot(entry: NavBackStackEntry, args: TabRootArgs) {
    if (LocalTwoPane.current) TwoPanePlaceholder() else TabRoot(entry, args)
}

/**
 * Seite von der Startseite [from] aus öffnen. In der linken Spalte ersetzt sie die rechte Spalte (ein neuer Tipp links
 * zeigt das andere Paar statt Seiten zu stapeln); sonst wie bisher ([narrow]).
 */
private fun NavHostController.openFromRoot(listPane: Boolean, from: NavBackStackEntry, route: ScreenRoute, narrow: () -> Unit) {
    if (listPane) replaceDetail(from, route) else narrow()
}

@Composable
private fun WatchlistRoot(entry: NavBackStackEntry, args: TabRootArgs) {
    val navigation = args.navigation
    val pane = LocalListPane.current
    WatchlistScreen(
        openWhyWatchId = args.whyWatchId(),
        onOpenWhyHandled = args.onWhyHandled,
        focusWatchId = args.focusWatchId(),
        onFocusHandled = args.onFocusHandled,
        // Doppel-Tipp (Glocke, «Ersten Alarm setzen») legt die Seite nur einmal auf den Stapel
        onAddClick = { if (pane) navigation.openExplorer() else navigation.openExplorerOnce(entry) },
        onOpenAlarms = { watchId ->
            navigation.openFromRoot(pane, entry, ScreenRoute.Alarms(watchId)) {
                navigation.navigateOnce(entry, ScreenRoute.Alarms(watchId))
            }
        },
        // Tipp auf eine Zeile: Paarseite (Doppeltipp legt sie nur einmal auf den Stapel)
        onOpenPair = { watchId ->
            navigation.openFromRoot(pane, entry, ScreenRoute.PairPage(watchId)) {
                navigation.navigateOnce(entry, ScreenRoute.PairPage(watchId))
            }
        },
        // Mitteilung bzw. Widget-Zeile: auch ohne Tipp, sobald die Merkliste geladen ist
        onFocusPair = { watchId ->
            navigation.openFromRoot(pane, entry, ScreenRoute.PairPage(watchId)) {
                navigation.navigate(ScreenRoute.PairPage(watchId))
            }
        },
        onOpenAllAlarms = {
            navigation.openFromRoot(pane, entry, ScreenRoute.AlarmsOverview()) {
                navigation.navigateOnce(entry, ScreenRoute.AlarmsOverview())
            }
        },
        onOpenActivitySettings = {
            navigation.openFromRoot(pane, entry, ScreenRoute.SettingsMarketAlerts) {
                navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true }
            }
        },
        onOpenAbout = {
            val about = ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)
            navigation.openFromRoot(pane, entry, about) { navigation.navigate(about) { launchSingleTop = true } }
        },
    )
}

@Composable
private fun MarketRoot(entry: NavBackStackEntry, args: TabRootArgs) {
    val navigation = args.navigation
    val pane = LocalListPane.current
    // «Rückgängig» nach «Löschen» auf der Paarseite: dieselbe Instanz wie die Merkliste (ihr Eintrag
    // ist als Startziel immer im Stapel); zur Not eine eigene für diesen Tab
    val watchlistOwner = remember(entry) {
        runCatching { navigation.getBackStackEntry<ScreenRoute.Watchlist>() }.getOrNull() ?: entry
    }
    // Erst beim ersten Öffnen erzeugt, danach bis zum Ende der Activity behalten
    MarketPhaseScreen(
        viewModel = hiltViewModel(viewModelStoreOwner = args.activityOwner),
        watchlistViewModel = hiltViewModel(viewModelStoreOwner = watchlistOwner),
        jump = MarketJump.fromName(args.marketJump()),
        onJumpHandled = { args.onMarketJumpChange(null) },
        // Coin aus «Heute auffällig» bzw. «Gewinner und Verlierer»: Paarseite (bzw. Vorschau)
        onOpenPair = { watchId, preview ->
            val route = ScreenRoute.PairPage(watchId, preview = preview)
            navigation.openFromRoot(pane, entry, route) { navigation.navigateOnce(entry, route) }
        },
        // Seite «Paar hinzufügen» über dem Markt-Tab: zurück führt wieder
        // hierher, nicht in die Merkliste (gleich wie iOS)
        onOpenExplorer = { query ->
            args.onExplorerSearch(query)
            if (pane) navigation.openExplorer() else navigation.openExplorerOnce(entry)
        },
        onOpenAbout = {
            val about = ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)
            navigation.openFromRoot(pane, entry, about) { navigation.navigate(about) { launchSingleTop = true } }
        },
        onOpenSection = { section ->
            val route = ScreenRoute.MarketSubpage(section.name)
            navigation.openFromRoot(pane, entry, route) { navigation.navigate(route) { launchSingleTop = true } }
        },
        // Mitteilung «Wirtschaftstermine»: Seite «Daten», die einmal zum Hinweis scrollt
        onOpenMacroData = {
            val route = ScreenRoute.MarketSubpage(com.cryptochecker.app.domain.market.MarketSection.DATA.name, macro = true)
            navigation.openFromRoot(pane, entry, route) { navigation.navigate(route) { launchSingleTop = true } }
        },
    )
}

@Composable
private fun PortfolioRoot(entry: NavBackStackEntry, args: TabRootArgs) {
    val navigation = args.navigation
    val pane = LocalListPane.current
    PortfolioGate(args.portfolioAccess(), args.requestUnlock, args.lockViewModel) {
        PortfolioScreen(
            onOpenCoin = { coin ->
                val route = ScreenRoute.PortfolioCoin(coin)
                navigation.openFromRoot(pane, entry, route) { navigation.navigate(route) }
            },
            onOpenTaxWorksheet = {
                navigation.openFromRoot(pane, entry, ScreenRoute.PortfolioTaxWorksheet) {
                    navigation.navigate(ScreenRoute.PortfolioTaxWorksheet) { launchSingleTop = true }
                }
            },
            onOpenAbout = {
                val about = ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)
                navigation.openFromRoot(pane, entry, about) { navigation.navigate(about) { launchSingleTop = true } }
            },
        )
    }
}

@Composable
private fun SettingsRoot(entry: NavBackStackEntry, args: TabRootArgs) {
    val navigation = args.navigation
    val pane = LocalListPane.current
    SettingsScreen(
        onOpenPage = { page ->
            val route = ScreenRoute.SettingsSubpage(page.name)
            navigation.openFromRoot(pane, entry, route) { navigation.navigate(route) { launchSingleTop = true } }
        },
        onOpenSearchTarget = { target ->
            args.onSettingsAnchorChange(target.anchor)
            val route: ScreenRoute? = when (target) {
                is SettingsSearchTarget.Page -> ScreenRoute.SettingsSubpage(target.page.name)
                is SettingsSearchTarget.MarketAlerts -> ScreenRoute.SettingsMarketAlerts
                is SettingsSearchTarget.Speech -> ScreenRoute.SettingsSpeech
                // Zeilen der Hauptseite hebt SettingsScreen selbst hervor
                is SettingsSearchTarget.Main -> null
            }
            if (route != null) {
                navigation.openFromRoot(pane, entry, route) { navigation.navigate(route) { launchSingleTop = true } }
            }
        },
    )
}
