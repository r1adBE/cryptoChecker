package com.cryptochecker.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
import com.cryptochecker.app.ui.features.alarms.AlarmsOverviewScreen
import com.cryptochecker.app.ui.features.alarms.AlarmsScreen
import com.cryptochecker.app.ui.features.alarms.LocalOpenUpdateSettings
import com.cryptochecker.app.ui.features.explorer.ExplorerScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerViewModel
import com.cryptochecker.app.ui.features.explorer.setSearchQuery
import com.cryptochecker.app.ui.features.info.MarketPhaseScreen
import com.cryptochecker.app.ui.features.info.MarketSectionScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioDetailScreen
import com.cryptochecker.app.ui.features.portfolio.PortfolioScreen
import com.cryptochecker.app.ui.features.settings.MarketAlertsSettingsScreen
import com.cryptochecker.app.ui.features.settings.ProvideSettingsHighlight
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.features.settings.SettingsPageScreen
import com.cryptochecker.app.ui.features.settings.SettingsScreen
import com.cryptochecker.app.ui.features.settings.SettingsSearchTarget
import com.cryptochecker.app.ui.features.settings.SpeechSettingsScreen
import com.cryptochecker.app.ui.features.watchlist.WatchlistScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.lock.PortfolioLockedState
import com.cryptochecker.app.ui.lock.PortfolioPendingState
import com.cryptochecker.app.ui.lock.SecureWindowEffect

/** Überblendung beim Wechsel von Tab oder Seite (ms). */
private const val NAV_FADE_MILLIS = 200

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
    // Sperre an und Portfolio (Tab oder Coin-Detail mit Verlauf) sichtbar: Fenster schützen
    val lockEnabled by lockViewModel.lockEnabled.collectAsStateWithLifecycle()
    SecureWindowEffect(
        PortfolioLockPolicy.secureWindow(
            lockSetting = lockEnabled,
            portfolioVisible = currentRoute == ScreenRoute.Portfolio || currentRoute == ScreenRoute.PortfolioCoin
        )
    )
    // Besitzer ausserhalb des Navigationsgraphen (die Activity): Der Markt-Tab behält
    // so sein ViewModel samt geladener Daten über Tab-Wechsel hinweg.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current) { "Kein ViewModelStoreOwner" }
    // «Was bewegt sich mit?» aus einem Alarm: Paar, dessen Blatt die Merkliste öffnen soll
    var whyWatchId by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<Long?>(null)
    }
    // Tipp auf Mitteilung bzw. Widget-Zeile: Paar, dessen Aktionsblatt die Merkliste öffnen soll
    var focusWatchId by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<Long?>(null)
    }
    // Suche, mit der die Seite «Paar hinzufügen» geöffnet werden soll («Heute auffällig» im Markt-Tab)
    var explorerSearch by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    // Suche in den Einstellungen: Punkt, den die nächste Unterseite kurz hervorhebt
    var settingsAnchor by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    // Verschlüsselte Daten ohne Schlüssel beiseitegelegt: einmal sagen, wie sie zurückkommen (über allen Tabs)
    com.cryptochecker.app.ui.features.settings.StorageRecoveryNoticeDialog()

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
                // «Paar hinzufügen» liegt über der Merkliste: zurück führt dorthin
                "add" -> {
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                    navigation.openExplorer()
                }
                "cycle" -> navigation.navigateToTab(ScreenRoute.MarketPhase)
                // Mitteilung «Wirtschaftstermine»: Markt-Tab, Register mit dem Hinweis (dort gescrollt)
                com.cryptochecker.app.ui.MainActivity.OPEN_MARKET_MACRO -> {
                    com.cryptochecker.app.ui.features.info.MarketRegister.jump =
                        com.cryptochecker.app.ui.features.info.MarketJump.MACRO
                    navigation.navigateToTab(ScreenRoute.MarketPhase)
                    // Eine gemerkte Unterseite schliessen: der Tab entscheidet, wohin es geht
                    navigation.popBackStack(ScreenRoute.MarketPhase, inclusive = false)
                }
                "alarms" -> navigation.navigate(ScreenRoute.AlarmsOverview) { launchSingleTop = true }
                // Portfolio-Widget: Portfolio-Tab; ist es aus, Einstellungen › Erweitert › Portfolio (dort der Schalter)
                "portfolio" -> when (PortfolioOff.widgetTarget(portfolioEnabled)) {
                    PortfolioOff.WidgetTarget.PORTFOLIO_TAB -> navigation.navigateToTab(ScreenRoute.Portfolio)
                    PortfolioOff.WidgetTarget.PORTFOLIO_SETTINGS -> {
                        navigation.navigateToTab(ScreenRoute.Settings)
                        // Eine gemerkte Unterseite schliessen, dann die Seite «Portfolio» darüber
                        navigation.popBackStack(ScreenRoute.Settings, inclusive = false)
                        navigation.navigate(ScreenRoute.settingsPage(SettingsPage.PORTFOLIO.name)) { launchSingleTop = true }
                    }
                }
                else -> com.cryptochecker.app.ui.MainActivity.whyWatchId(openTarget)?.let { id ->
                    whyWatchId = id
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                } ?: com.cryptochecker.app.ui.MainActivity.watchTargetId(openTarget)?.let { id ->
                    focusWatchId = id
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                    // Eine offene Unterseite über der Merkliste (z. B. «Alarme») schliessen
                    navigation.popBackStack(ScreenRoute.Watchlist, inclusive = false)
                }
            }
        }
        onOpenTargetHandled()
    }

    // Keine Begrüßung mehr (Runde 32): ein neuer Nutzer landet direkt in der Starter-Auswahl der
    // leeren Merkliste; «Was die App kann» steht unter Einstellungen › Über die App. Der Akku-Hinweis
    // kommt erst nach dem ersten Alarm, zurück auf einem Haupt-Tab (nicht über der Alarm-Bestätigung).
    BatteryOptimizationDialog(calm = currentRoute in bottomTabs.map { it.route })

    // K-10: «Aktualisierung ändern» im Alarm-Hinweis öffnet die Seite «Aktualisierung» direkt
    val openUpdateSettings: () -> Unit = remember(navigation) {
        { navigation.navigate(ScreenRoute.settingsPage(SettingsPage.UPDATES.name)) { launchSingleTop = true } }
    }
    CompositionLocalProvider(LocalOpenUpdateSettings provides openUpdateSettings) {
        Scaffold(
            bottomBar = {
                if (currentRoute in bottomTabs.map { it.route }) {
                    AppTabBar(
                        tabs = bottomTabs,
                        currentRoute = currentRoute,
                        onSelect = { route -> navigation.navigateToTab(route) }
                    )
                }
            }
        ) { innerPadding ->
            // Tab- und Seitenwechsel: kurze Überblendung (statt der 700 ms von Navigation);
            // ohne Bewegung (Animationen aus) sofort
            val reduceMotion = rememberReduceMotion()
            NavHost(
                navController = navigation,
                startDestination = ScreenRoute.Watchlist,
                // Tastatur (K-5): Die Tableiste bleibt unten und verschwindet hinter der Tastatur
                // (adjustResize, randlos – das Fenster wird weder verschoben noch verkleinert). Der Inhalt
                // bekommt nur den Teil der Tastatur, der über die Tableiste hinausragt, als Abstand –
                // so bleibt das fokussierte Feld sichtbar.
                modifier = Modifier
                    .padding(innerPadding)
                    .windowInsetsPadding(
                        WindowInsets.ime.exclude(WindowInsets(bottom = innerPadding.calculateBottomPadding()))
                    ),
                enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(NAV_FADE_MILLIS)) },
                exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(NAV_FADE_MILLIS)) },
            ) {
                composable(ScreenRoute.Watchlist) {
                    WatchlistScreen(
                        openWhyWatchId = whyWatchId,
                        onOpenWhyHandled = { whyWatchId = null },
                        focusWatchId = focusWatchId,
                        onFocusHandled = { focusWatchId = null },
                        onAddClick = { navigation.openExplorer() },
                        onOpenAlarms = { watchId -> navigation.navigate(ScreenRoute.alarms(watchId)) },
                        onOpenAllAlarms = { navigation.navigate(ScreenRoute.AlarmsOverview) },
                        onOpenActivitySettings = {
                            navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true }
                        },
                        onOpenAbout = {
                            navigation.navigate(ScreenRoute.settingsPage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
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
                        // Erst die Seite schliessen, dann zur Merkliste — so bleibt sie nicht im
                        // gemerkten Stapel des Markt-Tabs liegen
                        onOpenWatchlist = {
                            navigation.popBackStack()
                            navigation.navigateToTab(ScreenRoute.Watchlist)
                        },
                        onBack = { navigation.popBackStack() },
                        explorerViewModel = explorerViewModel
                    )
                }

                composable(ScreenRoute.Settings) {
                    SettingsScreen(
                        onOpenPage = { page ->
                            navigation.navigate(ScreenRoute.settingsPage(page.name)) { launchSingleTop = true }
                        },
                        onOpenSearchTarget = { target ->
                            settingsAnchor = target.anchor
                            val route = when (target) {
                                is SettingsSearchTarget.Page -> ScreenRoute.settingsPage(target.page.name)
                                is SettingsSearchTarget.MarketAlerts -> ScreenRoute.SettingsMarketAlerts
                                is SettingsSearchTarget.Speech -> ScreenRoute.SettingsSpeech
                                // Zeilen der Hauptseite hebt SettingsScreen selbst hervor
                                is SettingsSearchTarget.Main -> null
                            }
                            if (route != null) navigation.navigate(route) { launchSingleTop = true }
                        }
                    )
                }

                composable(
                    route = ScreenRoute.SettingsPageRoute,
                    arguments = listOf(
                        navArgument(ScreenRoute.SettingsPageArg) { type = NavType.StringType }
                    )
                ) { entry ->
                    SettingsHighlightEntry(settingsAnchor, onTaken = { settingsAnchor = null }) {
                        SettingsPageScreen(
                            page = SettingsPage.fromName(entry.arguments?.getString(ScreenRoute.SettingsPageArg)),
                            onBack = { navigation.popBackStack() },
                            // «Alarme», «Erweitert» und «Über» öffnen weitere Unterseiten; Zurück führt wieder dorthin.
                            // Ohne launchSingleTop: gleiche Route (settings/page/{page}) würde sonst den
                            // Eintrag «Alarme» ersetzen statt darüber zu legen
                            onOpenPage = { page ->
                                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    navigation.navigate(ScreenRoute.settingsPage(page.name))
                                }
                            },
                            // «Erweitert»: Markt-Meldungen und Sprachausgabe haben eigene Routen
                            onOpenMarketAlerts = {
                                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true }
                                }
                            },
                            onOpenSpeech = {
                                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    navigation.navigate(ScreenRoute.SettingsSpeech) { launchSingleTop = true }
                                }
                            }
                        )
                    }
                }

                composable(ScreenRoute.SettingsMarketAlerts) {
                    SettingsHighlightEntry(settingsAnchor, onTaken = { settingsAnchor = null }) {
                        MarketAlertsSettingsScreen(onBack = { navigation.popBackStack() })
                    }
                }

                composable(ScreenRoute.SettingsSpeech) {
                    SettingsHighlightEntry(settingsAnchor, onTaken = { settingsAnchor = null }) {
                        SpeechSettingsScreen(onBack = { navigation.popBackStack() })
                    }
                }

                composable(ScreenRoute.MarketPhase) { entry ->
                    // Aktionsblatt aus «Heute auffällig»: dieselbe Instanz wie die Merkliste (ihr Eintrag
                    // ist als Startziel immer im Stapel); zur Not eine eigene für diesen Tab
                    val watchlistOwner = androidx.compose.runtime.remember(entry) {
                        runCatching { navigation.getBackStackEntry(ScreenRoute.Watchlist) }.getOrNull() ?: entry
                    }
                    // Erst beim ersten Öffnen erzeugt, danach bis zum Ende der Activity behalten
                    MarketPhaseScreen(
                        viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
                        watchlistViewModel = hiltViewModel(viewModelStoreOwner = watchlistOwner),
                        lockViewModel = lockViewModel,
                        onOpenAlarms = { watchId -> navigation.navigate(ScreenRoute.alarms(watchId)) },
                        // Seite «Paar hinzufügen» über dem Markt-Tab (G-13): zurück führt wieder
                        // hierher, nicht in die Merkliste (gleich wie iOS)
                        onOpenExplorer = { query ->
                            explorerSearch = query
                            navigation.openExplorer()
                        },
                        onOpenAbout = {
                            navigation.navigate(ScreenRoute.settingsPage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
                        },
                        onOpenSection = { section ->
                            navigation.navigate(ScreenRoute.marketSection(section.name)) { launchSingleTop = true }
                        }
                    )
                }

                // G-10: «Einordnung» bzw. «Daten» als eigene Seite über dem Markt-Tab (ohne Tableiste);
                // dasselbe ViewModel wie der Tab, die Daten stehen also schon
                composable(
                    route = ScreenRoute.MarketSectionRoute,
                    arguments = listOf(
                        navArgument(ScreenRoute.MarketSectionArg) { type = NavType.StringType }
                    )
                ) { entry ->
                    MarketSectionScreen(
                        section = MarketSections.subpage(entry.arguments?.getString(ScreenRoute.MarketSectionArg)),
                        viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
                        onBack = { navigation.popBackStack() }
                    )
                }

                composable(ScreenRoute.Portfolio) {
                    PortfolioGate(portfolioAccess, requestUnlock) {
                        PortfolioScreen(
                            onOpenCoin = { coin -> navigation.navigate(ScreenRoute.portfolioCoin(coin)) },
                            onOpenAbout = {
                                navigation.navigate(ScreenRoute.settingsPage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
                            }
                        )
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
}

/**
 * Unterseite der Einstellungen, geöffnet aus der Suche: übernimmt den Anker beim ersten Aufbau
 * (danach geleert, damit spätere Besuche nichts hervorheben) und gibt ihn an die Punkte weiter.
 */
@Composable
private fun SettingsHighlightEntry(anchor: String?, onTaken: () -> Unit, content: @Composable () -> Unit) {
    val taken = remember { anchor }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (taken != null) onTaken() }
    ProvideSettingsHighlight(taken, content)
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

/** Seite «Paar hinzufügen» über dem aktuellen Tab (eigene Seite mit Zurück, ohne Tableiste). */
private fun NavHostController.openExplorer() {
    navigate(ScreenRoute.Explorer) { launchSingleTop = true }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
