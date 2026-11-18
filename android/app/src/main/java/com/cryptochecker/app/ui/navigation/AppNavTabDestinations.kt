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
import com.cryptochecker.app.domain.lock.PortfolioAccess
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.ui.features.explorer.ExplorerScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerSectionsState
import com.cryptochecker.app.ui.features.explorer.ExplorerViewModel
import com.cryptochecker.app.ui.features.explorer.setSearchQuery
import com.cryptochecker.app.ui.features.info.MarketSectionScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioDetailScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioViewModel
import com.cryptochecker.app.ui.features.portfolio.TaxWorksheetScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel

/** Merkliste (Startseite: [TabRoot]). */
internal fun NavGraphBuilder.watchlistDestination(args: TabRootArgs) {
    composable<ScreenRoute.Watchlist> { entry -> TabRootSlot(entry, args) }
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

/** Markt-Tab (Startseite: [TabRoot]) und seine Unterseiten. */
internal fun NavGraphBuilder.marketDestinations(
    navigation: NavHostController,
    activityOwner: ViewModelStoreOwner,
    args: TabRootArgs,
) {
    composable<ScreenRoute.MarketPhase> { entry -> TabRootSlot(entry, args) }

    // «Einordnung» bzw. «Daten» als eigene Seite über dem Markt-Tab (ohne Tableiste);
    // dasselbe ViewModel wie der Tab, die Daten stehen also schon
    composable<ScreenRoute.MarketSubpage> { entry ->
        val route = entry.toRoute<ScreenRoute.MarketSubpage>()
        // Gelöscht wird im SavedStateHandle (übersteht Prozess-Neustart): kein zweites Scrollen
        val macroJump by entry.savedStateHandle.getStateFlow(ScreenRoute.MARKET_SUBPAGE_ARG_MACRO, route.macro)
            .collectAsStateWithLifecycle()
        MarketSectionScreen(
            section = MarketSections.subpage(route.section),
            viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
            onBack = { navigation.popBackStack() },
            macroJump = macroJump,
            onMacroJumpHandled = { entry.savedStateHandle[ScreenRoute.MARKET_SUBPAGE_ARG_MACRO] = false },
        )
    }
}

/** Portfolio-Tab, Coin-Detail und Steuer-Arbeitsunterlage, je hinter der Sperre ([PortfolioGate]). */
internal fun NavGraphBuilder.portfolioDestinations(
    navigation: NavHostController,
    portfolioAccess: () -> PortfolioAccess,
    requestUnlock: () -> Unit,
    lockViewModel: PortfolioLockViewModel,
    args: TabRootArgs,
) {
    composable<ScreenRoute.Portfolio> { entry -> TabRootSlot(entry, args) }

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
