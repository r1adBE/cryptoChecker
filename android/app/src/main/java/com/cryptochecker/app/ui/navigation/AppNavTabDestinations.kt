// Ziele des Navigationsgraphen (Teil von `AppNavHost`): Merkliste, «Paar hinzufügen», Markt-Tab samt Unterseiten und
// Portfolio samt Unterseiten. Eigene Datei, weil `AppNavHost` sonst zu lang würde; der Zustand (Sprünge, Suche) bleibt
// dort und kommt als Lese- bzw. Setz-Funktion herein, damit jede Seite ihn beim Zeichnen frisch liest.
package com.cryptochecker.app.ui.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.ui.features.explorer.ExplorerScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerSectionsState
import com.cryptochecker.app.ui.features.explorer.ExplorerViewModel
import com.cryptochecker.app.ui.features.explorer.setSearchQuery
import com.cryptochecker.app.ui.features.info.MarketJump
import com.cryptochecker.app.ui.features.info.MarketPhaseScreen
import com.cryptochecker.app.ui.features.info.MarketSectionScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioDetailScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioViewModel
import com.cryptochecker.app.ui.features.portfolio.TaxWorksheetScreen
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.features.watchlist.WatchlistScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel

/**
 * Merkliste. [whyWatchId]: Paar, dessen «Was bewegt sich mit?»-Blatt sie öffnen soll (aus einem Alarm); [focusWatchId]:
 * Paar, dessen Seite sie öffnen soll (Tipp auf Mitteilung bzw. Widget-Zeile).
 */
internal fun NavGraphBuilder.watchlistDestination(
    navigation: NavHostController,
    whyWatchId: () -> Long?,
    onWhyHandled: () -> Unit,
    focusWatchId: () -> Long?,
    onFocusHandled: () -> Unit,
) {
    composable<ScreenRoute.Watchlist> { entry ->
        WatchlistScreen(
            openWhyWatchId = whyWatchId(),
            onOpenWhyHandled = onWhyHandled,
            focusWatchId = focusWatchId(),
            onFocusHandled = onFocusHandled,
            // Doppel-Tipp (Glocke, «Ersten Alarm setzen») legt die Seite nur einmal auf den Stapel
            onAddClick = { navigation.openExplorerOnce(entry) },
            onOpenAlarms = { watchId -> navigation.navigateOnce(entry, ScreenRoute.Alarms(watchId)) },
            // Tipp auf eine Zeile: Paarseite (Doppeltipp legt sie nur einmal auf den Stapel)
            onOpenPair = { watchId -> navigation.navigateOnce(entry, ScreenRoute.PairPage(watchId)) },
            // Mitteilung bzw. Widget-Zeile: auch ohne Tipp, sobald die Merkliste geladen ist
            onFocusPair = { watchId -> navigation.navigate(ScreenRoute.PairPage(watchId)) },
            onOpenAllAlarms = { navigation.navigateOnce(entry, ScreenRoute.AlarmsOverview()) },
            onOpenActivitySettings = {
                navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true }
            },
            onOpenAbout = {
                navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
            }
        )
    }
}

/** «Paar hinzufügen»; [search]: Suche, mit der die Seite geöffnet werden soll («Heute auffällig» im Markt-Tab). */
internal fun NavGraphBuilder.explorerDestination(
    navigation: NavHostController,
    search: () -> String?,
    onSearchHandled: () -> Unit,
    sections: ExplorerSectionsState,
) {
    composable<ScreenRoute.Explorer> {
        val explorerViewModel: ExplorerViewModel = hiltViewModel()
        val explorerSearch = search()
        androidx.compose.runtime.LaunchedEffect(explorerSearch) {
            explorerSearch?.let { query ->
                explorerViewModel.setSearchQuery(query)
                onSearchHandled()
            }
        }
        ExplorerScreen(
            // Erst die Seite schliessen, dann zur Merkliste — so bleibt sie nicht im
            // gemerkten Stapel des Markt-Tabs liegen
            onOpenWatchlist = {
                navigation.popBackStack()
                navigation.navigateToTab(ScreenRoute.Watchlist)
            },
            onBack = { navigation.popBackStack() },
            explorerViewModel = explorerViewModel,
            sections = sections,
        )
    }
}

/** Markt-Tab und seine Unterseiten; [marketJump]: offener Sprung von aussen ([MarketJump.name]). */
internal fun NavGraphBuilder.marketDestinations(
    navigation: NavHostController,
    activityOwner: ViewModelStoreOwner,
    marketJump: () -> String?,
    onMarketJumpChange: (String?) -> Unit,
    onExplorerSearch: (String) -> Unit,
) {
    composable<ScreenRoute.MarketPhase> { entry ->
        // «Rückgängig» nach «Löschen» auf der Paarseite: dieselbe Instanz wie die Merkliste (ihr Eintrag
        // ist als Startziel immer im Stapel); zur Not eine eigene für diesen Tab
        val watchlistOwner = androidx.compose.runtime.remember(entry) {
            runCatching { navigation.getBackStackEntry<ScreenRoute.Watchlist>() }.getOrNull() ?: entry
        }
        // Erst beim ersten Öffnen erzeugt, danach bis zum Ende der Activity behalten
        MarketPhaseScreen(
            viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
            watchlistViewModel = hiltViewModel(viewModelStoreOwner = watchlistOwner),
            jump = MarketJump.fromName(marketJump()),
            onJumpHandled = { onMarketJumpChange(null) },
            // Coin aus «Heute auffällig» bzw. «Gewinner und Verlierer»: Paarseite (bzw. Vorschau)
            onOpenPair = { watchId, preview ->
                navigation.navigateOnce(entry, ScreenRoute.PairPage(watchId, preview = preview))
            },
            // Seite «Paar hinzufügen» über dem Markt-Tab: zurück führt wieder
            // hierher, nicht in die Merkliste (gleich wie iOS)
            onOpenExplorer = { query ->
                onExplorerSearch(query)
                navigation.openExplorerOnce(entry)
            },
            onOpenAbout = {
                navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
            },
            onOpenSection = { section ->
                navigation.navigate(ScreenRoute.MarketSubpage(section.name)) { launchSingleTop = true }
            },
            // Mitteilung «Wirtschaftstermine»: Seite «Daten», die einmal zum Hinweis scrollt
            onOpenMacroData = {
                navigation.navigate(
                    ScreenRoute.MarketSubpage(com.cryptochecker.app.domain.market.MarketSection.DATA.name, macro = true)
                ) { launchSingleTop = true }
            }
        )
    }

    // «Einordnung» bzw. «Daten» als eigene Seite über dem Markt-Tab (ohne Tableiste);
    // dasselbe ViewModel wie der Tab, die Daten stehen also schon
    composable<ScreenRoute.MarketSubpage> { entry ->
        val route = entry.toRoute<ScreenRoute.MarketSubpage>()
        // Gelöscht wird im SavedStateHandle (übersteht Prozess-Neustart): kein zweites Scrollen
        val macroJump by entry.savedStateHandle.getStateFlow(ScreenRoute.MarketSubpageArgMacro, route.macro)
            .collectAsStateWithLifecycle()
        MarketSectionScreen(
            section = MarketSections.subpage(route.section),
            viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
            onBack = { navigation.popBackStack() },
            macroJump = macroJump,
            onMacroJumpHandled = { entry.savedStateHandle[ScreenRoute.MarketSubpageArgMacro] = false },
        )
    }
}

/** Portfolio-Tab, Coin-Detail und Steuer-Arbeitsunterlage, je hinter der Sperre ([PortfolioGate]). */
internal fun NavGraphBuilder.portfolioDestinations(
    navigation: NavHostController,
    portfolioAccess: () -> PortfolioAccess,
    requestUnlock: () -> Unit,
    lockViewModel: PortfolioLockViewModel,
) {
    composable<ScreenRoute.Portfolio> {
        PortfolioGate(portfolioAccess(), requestUnlock, lockViewModel) {
            PortfolioScreen(
                onOpenCoin = { coin -> navigation.navigate(ScreenRoute.PortfolioCoin(coin)) },
                onOpenTaxWorksheet = {
                    navigation.navigate(ScreenRoute.PortfolioTaxWorksheet) { launchSingleTop = true }
                },
                onOpenAbout = {
                    navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
                }
            )
        }
    }

    composable<ScreenRoute.PortfolioCoin> { entry ->
        // Dieselbe PortfolioViewModel-Instanz wie der Tab (keine doppelten Abrufe); fehlt
        // dessen Eintrag im Stapel (heute nie, nur aus dem Tab erreichbar), zur Not eine eigene
        val portfolioOwner = androidx.compose.runtime.remember(entry) {
            runCatching { navigation.getBackStackEntry<ScreenRoute.Portfolio>() }.getOrNull() ?: entry
        }
        PortfolioGate(portfolioAccess(), requestUnlock, lockViewModel) {
            PortfolioDetailScreen(
                coin = entry.toRoute<ScreenRoute.PortfolioCoin>().coin,
                onBack = { navigation.popBackStack() },
                viewModel = hiltViewModel(viewModelStoreOwner = portfolioOwner),
            )
        }
    }

    composable<ScreenRoute.PortfolioTaxWorksheet> { entry ->
        // Anzeigewährung aus der Instanz des Tabs (wie das Coin-Detail)
        val portfolioOwner = androidx.compose.runtime.remember(entry) {
            runCatching { navigation.getBackStackEntry<ScreenRoute.Portfolio>() }.getOrNull() ?: entry
        }
        val portfolio: PortfolioViewModel = hiltViewModel(viewModelStoreOwner = portfolioOwner)
        val currency by portfolio.currency.collectAsStateWithLifecycle()
        PortfolioGate(portfolioAccess(), requestUnlock, lockViewModel) {
            TaxWorksheetScreen(currency = currency, onBack = { navigation.popBackStack() })
        }
    }
}
