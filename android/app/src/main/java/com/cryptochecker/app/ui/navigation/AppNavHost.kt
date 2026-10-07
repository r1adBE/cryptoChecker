package com.cryptochecker.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cryptochecker.app.R
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
import com.cryptochecker.app.ui.features.about.WelcomeDialog
import com.cryptochecker.app.ui.features.alarms.AlarmsOverviewScreen
import com.cryptochecker.app.ui.features.alarms.AlarmsScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerViewModel
import com.cryptochecker.app.ui.features.info.MarketPhaseScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioDetailScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioScreen
import com.cryptochecker.app.ui.features.settings.MarketAlertsSettingsScreen
import com.cryptochecker.app.ui.features.settings.SettingsScreen
import com.cryptochecker.app.ui.features.settings.SpeechSettingsScreen
import com.cryptochecker.app.ui.features.watchlist.WatchlistScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.lock.PortfolioLockedState
import com.cryptochecker.app.ui.lock.PortfolioPendingState

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val iconRes: Int,
)

/**
 * Merkliste · Hinzufügen · Zyklus · (Portfolio) · Optionen.
 * Der Portfolio-Tab erscheint nur, wenn er in den Optionen eingeschaltet ist.
 */
private fun buildBottomTabs(portfolioEnabled: Boolean) = buildList {
    add(BottomTab(ScreenRoute.Watchlist, R.string.tab_watchlist, R.drawable.ic_list))
    add(BottomTab(ScreenRoute.Explorer, R.string.tab_markets, R.drawable.ic_add))
    add(BottomTab(ScreenRoute.MarketPhase, R.string.tab_market_phase, R.drawable.ic_cycle))
    if (portfolioEnabled) add(BottomTab(ScreenRoute.Portfolio, R.string.portfolio_title, R.drawable.ic_portfolio))
    add(BottomTab(ScreenRoute.Settings, R.string.tab_settings, R.drawable.ic_settings))
}

@Composable
fun AppNavHost(
    navigation: NavHostController = rememberNavController(),
    openTarget: String? = null,
    onOpenTargetHandled: () -> Unit = {},
    navViewModel: AppNavViewModel = hiltViewModel(),
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
) {
    val backStackEntry by navigation.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val portfolioEnabled by navViewModel.portfolioEnabled.collectAsStateWithLifecycle()
    val bottomTabs = remember(portfolioEnabled) { buildBottomTabs(portfolioEnabled) }
    // Portfolio-Sperre: gilt nur für den Portfolio-Tab und seine Unterseiten
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val unlockReason = stringResource(R.string.portfolio_lock_reason)
    val requestUnlock = { lockViewModel.requireUnlock(context.findFragmentActivity(), unlockReason) {} }
    // Besitzer ausserhalb des Navigationsgraphen (die Activity): Der Markt-Tab behält
    // so sein ViewModel samt geladener Daten über Tab-Wechsel hinweg.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current) { "Kein ViewModelStoreOwner" }
    // Suche, mit der der Hinzufügen-Tab geöffnet werden soll («Heute auffällig» im Markt-Tab)
    var explorerSearch by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    // Gesperrt, während eine Coin-Detailansicht offen ist: zurück zum (gesperrten) Portfolio-Tab
    androidx.compose.runtime.LaunchedEffect(portfolioAccess, currentRoute) {
        if (portfolioAccess == PortfolioAccess.LOCKED && currentRoute == ScreenRoute.PortfolioCoin) {
            runCatching { navigation.popBackStack(ScreenRoute.Portfolio, inclusive = false) }
        }
    }

    // Portfolio ausgeschaltet, während es offen ist: zurück zur Merkliste
    androidx.compose.runtime.LaunchedEffect(portfolioEnabled, currentRoute) {
        if (!portfolioEnabled &&
            (currentRoute == ScreenRoute.Portfolio || currentRoute == ScreenRoute.PortfolioCoin)
        ) {
            runCatching { navigation.navigateToTab(ScreenRoute.Watchlist) }
        }
    }

    // App-Verknüpfung: direkt zum gewünschten Bereich springen
    androidx.compose.runtime.LaunchedEffect(openTarget) {
        if (openTarget == null) return@LaunchedEffect
        // Der Graph steht erst nach dem ersten Aufbau — Fehler hier nie zum Absturz werden lassen.
        runCatching {
            when (openTarget) {
                "add" -> navigation.navigateToTab(ScreenRoute.Explorer)
                "cycle" -> navigation.navigateToTab(ScreenRoute.MarketPhase)
                "alarms" -> navigation.navigate(ScreenRoute.AlarmsOverview) { launchSingleTop = true }
                // Portfolio-Widget: nur wenn der Tab eingeschaltet ist, sonst bleibt die Merkliste
                "portfolio" -> if (portfolioEnabled) navigation.navigateToTab(ScreenRoute.Portfolio) else Unit
            }
        }
        onOpenTargetHandled()
    }

    // Einmalige Begrüßung nach der Installation. Der Akku-Hinweis kommt erst nach dem
    // ersten Alarm, zurück auf einem Haupt-Tab (nicht über der Alarm-Bestätigung).
    WelcomeDialog(onStart = { navigation.navigateToTab(ScreenRoute.Watchlist) })
    BatteryOptimizationDialog(calm = currentRoute in bottomTabs.map { it.route })

    Scaffold(
        bottomBar = {
            if (currentRoute in bottomTabs.map { it.route }) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navigation.navigateToTab(tab.route) },
                            icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
                            label = { TabLabel(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navigation,
            startDestination = ScreenRoute.Watchlist,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(ScreenRoute.Watchlist) {
                WatchlistScreen(
                    onAddClick = { navigation.navigateToTab(ScreenRoute.Explorer) },
                    onOpenAlarms = { watchId -> navigation.navigate(ScreenRoute.alarms(watchId)) },
                    onOpenAllAlarms = { navigation.navigate(ScreenRoute.AlarmsOverview) },
                    onOpenActivitySettings = {
                        navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true }
                    }
                )
            }

            composable(ScreenRoute.Explorer) {
                val explorerViewModel: ExplorerViewModel = hiltViewModel()
                androidx.compose.runtime.LaunchedEffect(explorerSearch) {
                    explorerSearch?.let { query ->
                        explorerViewModel.setSearchQuery(query)
                        explorerSearch = null
                    }
                }
                ExplorerScreen(
                    onOpenWatchlist = { navigation.navigateToTab(ScreenRoute.Watchlist) },
                    explorerViewModel = explorerViewModel
                )
            }

            composable(ScreenRoute.Settings) {
                SettingsScreen(
                    onOpenMarketAlerts = { navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true } },
                    onOpenSpeech = { navigation.navigate(ScreenRoute.SettingsSpeech) { launchSingleTop = true } }
                )
            }

            composable(ScreenRoute.SettingsMarketAlerts) {
                MarketAlertsSettingsScreen(onBack = { navigation.popBackStack() })
            }

            composable(ScreenRoute.SettingsSpeech) {
                SpeechSettingsScreen(onBack = { navigation.popBackStack() })
            }

            composable(ScreenRoute.MarketPhase) {
                // Erst beim ersten Öffnen erzeugt, danach bis zum Ende der Activity behalten
                MarketPhaseScreen(
                    viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
                    onOpenExplorer = { query ->
                        explorerSearch = query
                        navigation.navigateToTab(ScreenRoute.Explorer)
                    }
                )
            }

            composable(ScreenRoute.Portfolio) {
                PortfolioGate(portfolioAccess, requestUnlock) {
                    PortfolioScreen(onOpenCoin = { coin -> navigation.navigate(ScreenRoute.portfolioCoin(coin)) })
                }
            }

            composable(
                route = ScreenRoute.PortfolioCoin,
                arguments = listOf(
                    navArgument(ScreenRoute.PortfolioArgCoin) { type = NavType.StringType }
                )
            ) { entry ->
                PortfolioGate(portfolioAccess, requestUnlock) {
                    PortfolioDetailScreen(
                        coin = entry.arguments?.getString(ScreenRoute.PortfolioArgCoin).orEmpty(),
                        onBack = { navigation.popBackStack() }
                    )
                }
            }

            composable(
                route = ScreenRoute.Alarms,
                arguments = listOf(
                    navArgument(ScreenRoute.AlarmsArgWatchId) { type = NavType.LongType }
                )
            ) {
                AlarmsScreen(onBack = { navigation.popBackStack() })
            }

            composable(ScreenRoute.AlarmsOverview) {
                AlarmsOverviewScreen(
                    onBack = { navigation.popBackStack() },
                    onOpenWatch = { watchId -> navigation.navigate(ScreenRoute.alarms(watchId)) }
                )
            }
        }
    }
}

/** Inhalt des Portfolios nur frei; gesperrt der ruhige Sperr-Zustand, beim Kaltstart leer. */
@Composable
private fun PortfolioGate(access: PortfolioAccess, onUnlock: () -> Unit, content: @Composable () -> Unit) {
    when (access) {
        PortfolioAccess.OPEN -> content()
        PortfolioAccess.LOCKED -> PortfolioLockedState(onUnlock = onUnlock)
        PortfolioAccess.PENDING -> PortfolioPendingState()
    }
}

/**
 * Tab-Beschriftung auf einer Zeile: Lange Wörter («Hinzufügen», Übersetzungen) werden auf
 * schmalen Geräten schrittweise verkleinert statt abgeschnitten. Stil und Farbe kommen vom
 * NavigationBarItem (LocalTextStyle / LocalContentColor), damit Auswahlfarbe und Animation bleiben.
 */
@Composable
private fun TabLabel(text: String) {
    val style = LocalTextStyle.current
    val maxSize = if (style.fontSize.isSp && style.fontSize.value >= 9f) style.fontSize else 12.sp
    BasicText(
        text = text,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = maxSize, stepSize = 0.5.sp)
    )
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
