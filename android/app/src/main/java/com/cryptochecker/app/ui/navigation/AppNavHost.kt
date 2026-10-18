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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
import com.cryptochecker.app.ui.features.alarms.AlarmsOverviewScreen
import com.cryptochecker.app.ui.features.alarms.AlarmsScreen
import com.cryptochecker.app.ui.features.alarms.AlarmLogScreen
import com.cryptochecker.app.ui.features.alarms.AlarmDeliveryScreen
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmDelivery
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmLog
import com.cryptochecker.app.ui.features.alarms.LocalOpenUpdateSettings
import com.cryptochecker.app.ui.features.explorer.ExplorerScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerViewModel
import com.cryptochecker.app.ui.features.explorer.rememberExplorerSections
import com.cryptochecker.app.ui.features.explorer.setSearchQuery
import com.cryptochecker.app.ui.features.info.MarketJump
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
    val currentDestination = backStackEntry?.destination
    // Muster der aktuellen Route (nur als Schlüssel der Effekte unten, verglichen wird typsicher)
    val currentRoute = currentDestination?.route
    val onPortfolioCoin = currentDestination?.hasRoute<ScreenRoute.PortfolioCoin>() == true
    val onPortfolio = onPortfolioCoin || currentDestination?.hasRoute<ScreenRoute.Portfolio>() == true
    val portfolioEnabled by navViewModel.portfolioEnabled.collectAsStateWithLifecycle()
    val bottomTabs = remember(portfolioEnabled) { buildBottomTabs(portfolioEnabled) }
    // Gewählter Tab; null auf Seiten ohne Tableiste (Alarme, Unterseiten, «Paar hinzufügen»)
    val currentTab = bottomTabs.firstOrNull { tab -> currentDestination?.hasRoute(tab.route::class) == true }?.route
    // Portfolio-Sperre: gilt nur für den Portfolio-Tab und seine Unterseiten
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val unlockReason = stringResource(R.string.portfolio_lock_reason)
    val requestUnlock = { lockViewModel.requireUnlock(context.findFragmentActivity(), unlockReason) {} }
    // Sperre an und Portfolio (Tab oder Coin-Detail mit Verlauf) sichtbar: Fenster schützen;
    // vor Android 13 mit Sperre jeder Bildschirm (dort fehlt setRecentsScreenshotEnabled)
    val lockEnabled by lockViewModel.lockEnabled.collectAsStateWithLifecycle()
    SecureWindowEffect(
        PortfolioLockPolicy.secureWindow(
            lockSetting = lockEnabled,
            portfolioVisible = onPortfolio,
            sdkInt = android.os.Build.VERSION.SDK_INT,
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
    // Offener Sprung von aussen in den Markt-Tab ([MarketJump.name]); der Tab löscht ihn, sobald er entschieden hat
    var marketJump by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    // Auf-/zugeklappte Bereiche von «Paar hinzufügen»: gelten für die ganze Sitzung (jedes Öffnen der Seite)
    val explorerSections = rememberExplorerSections()

    // Suche in den Einstellungen: Punkt, den die nächste Unterseite kurz hervorhebt
    var settingsAnchor by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    // Verschlüsselte Daten ohne Schlüssel beiseitegelegt: sagen, wie sie zurückkommen (über allen Tabs, bis bestätigt);
    // «Sicherung wiederherstellen» öffnet Einstellungen › Sicherung mit hervorgehobenem «Wiederherstellen»
    com.cryptochecker.app.ui.features.settings.StorageRecoveryNoticeDialog(
        onRestoreBackup = {
            settingsAnchor = "backup.actions"
            navigation.navigateToTab(ScreenRoute.Settings)
            navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.BACKUP.name)) { launchSingleTop = true }
        }
    )

    // Gesperrt, während eine Coin-Detailansicht offen ist: zurück zum (gesperrten) Portfolio-Tab
    androidx.compose.runtime.LaunchedEffect(portfolioAccess, currentRoute) {
        if (portfolioAccess == PortfolioAccess.LOCKED && onPortfolioCoin) {
            runCatchingCancellable { navigation.popBackStack<ScreenRoute.Portfolio>(inclusive = false) }
        }
    }

    // Portfolio ausgeschaltet, während es offen ist: zurück zur Merkliste
    androidx.compose.runtime.LaunchedEffect(portfolioEnabled, currentRoute) {
        if (!portfolioEnabled && onPortfolio) {
            runCatchingCancellable { navigation.navigateToTab(ScreenRoute.Watchlist) }
        }
    }

    // App-Verknüpfung: direkt zum gewünschten Bereich springen
    androidx.compose.runtime.LaunchedEffect(openTarget) {
        if (openTarget == null) return@LaunchedEffect
        // Der Graph steht erst nach dem ersten Aufbau — Fehler hier nie zum Absturz werden lassen.
        runCatchingCancellable {
            when (openTarget) {
                // «Paar hinzufügen» liegt über der Merkliste: zurück führt dorthin
                "add" -> {
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                    navigation.openExplorer()
                }
                "cycle" -> navigation.navigateToTab(ScreenRoute.MarketPhase)
                // Mitteilung «Wirtschaftstermine»: Markt-Tab, Register mit dem Hinweis (dort gescrollt)
                com.cryptochecker.app.ui.MainActivity.OPEN_MARKET_MACRO -> {
                    marketJump = MarketJump.MACRO.name
                    navigation.navigateToTab(ScreenRoute.MarketPhase)
                    // Eine gemerkte Unterseite schliessen: der Tab entscheidet, wohin es geht
                    navigation.popBackStack<ScreenRoute.MarketPhase>(inclusive = false)
                }
                "alarms" -> navigation.navigate(ScreenRoute.AlarmsOverview) { launchSingleTop = true }
                // Portfolio-Widget: Portfolio-Tab; ist es aus, Einstellungen › Erweitert › Portfolio (dort der Schalter)
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
                    whyWatchId = id
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                } ?: com.cryptochecker.app.ui.MainActivity.watchTargetId(openTarget)?.let { id ->
                    focusWatchId = id
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                    // Eine offene Unterseite über der Merkliste (z. B. «Alarme») schliessen
                    navigation.popBackStack<ScreenRoute.Watchlist>(inclusive = false)
                }
            }
        }
        onOpenTargetHandled()
    }

    // Keine Begrüssung mehr (Runde 32): ein neuer Nutzer landet direkt in der Starter-Auswahl der
    // leeren Merkliste; «Was die App kann» steht unter Einstellungen › Über die App. Der Akku-Hinweis
    // kommt erst nach dem ersten Alarm, zurück auf einem Haupt-Tab (nicht über der Alarm-Bestätigung).
    BatteryOptimizationDialog(calm = currentTab != null)

    // K-10: «Aktualisierung ändern» im Alarm-Hinweis öffnet die Seite «Aktualisierung» direkt
    val openUpdateSettings: () -> Unit = remember(navigation) {
        { navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.UPDATES.name)) { launchSingleTop = true } }
    }
    // Alarm-Protokoll: auch aus den Einstellungen («Aktualisierung», neben «Letzte Prüfungen»)
    val openAlarmLog: () -> Unit = remember(navigation) {
        { navigation.navigate(ScreenRoute.AlarmLog) { launchSingleTop = true } }
    }
    // «Damit Alarme ankommen»: aus «Alle Alarme», dem Hinweis im Alarm-Editor und Einstellungen › Alarme
    val openAlarmDelivery: () -> Unit = remember(navigation) {
        { navigation.navigate(ScreenRoute.AlarmDelivery) { launchSingleTop = true } }
    }
    // Erneutes Tippen auf den gewählten Tab: an den Anfang rollen (wie iOS)
    val tabReselect = remember { TabReselectSignal() }
    CompositionLocalProvider(
        LocalOpenUpdateSettings provides openUpdateSettings,
        LocalOpenAlarmLog provides openAlarmLog,
        LocalOpenAlarmDelivery provides openAlarmDelivery,
        LocalTabReselect provides tabReselect,
    ) {
        Scaffold(
            bottomBar = {
                if (currentTab != null) {
                    AppTabBar(
                        tabs = bottomTabs,
                        currentRoute = currentTab,
                        onSelect = { route ->
                            if (route == currentTab) {
                                // Schon gewählt: allfällige Unterseite schliessen, an den Anfang rollen
                                navigation.popBackStack(route, inclusive = false)
                                tabReselect.request(route)
                            } else {
                                navigation.navigateToTab(route)
                            }
                        }
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
                composable<ScreenRoute.Watchlist> {
                    WatchlistScreen(
                        openWhyWatchId = whyWatchId,
                        onOpenWhyHandled = { whyWatchId = null },
                        focusWatchId = focusWatchId,
                        onFocusHandled = { focusWatchId = null },
                        onAddClick = { navigation.openExplorer() },
                        onOpenAlarms = { watchId -> navigation.navigate(ScreenRoute.Alarms(watchId)) },
                        onOpenAllAlarms = { navigation.navigate(ScreenRoute.AlarmsOverview) },
                        onOpenActivitySettings = {
                            navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true }
                        },
                        onOpenAbout = {
                            navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
                        }
                    )
                }

                composable<ScreenRoute.Explorer> {
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
                        explorerViewModel = explorerViewModel,
                        sections = explorerSections,
                    )
                }

                composable<ScreenRoute.Settings> {
                    SettingsScreen(
                        onOpenPage = { page ->
                            navigation.navigate(ScreenRoute.SettingsSubpage(page.name)) { launchSingleTop = true }
                        },
                        onOpenSearchTarget = { target ->
                            settingsAnchor = target.anchor
                            val route: ScreenRoute? = when (target) {
                                is SettingsSearchTarget.Page -> ScreenRoute.SettingsSubpage(target.page.name)
                                is SettingsSearchTarget.MarketAlerts -> ScreenRoute.SettingsMarketAlerts
                                is SettingsSearchTarget.Speech -> ScreenRoute.SettingsSpeech
                                // Zeilen der Hauptseite hebt SettingsScreen selbst hervor
                                is SettingsSearchTarget.Main -> null
                            }
                            if (route != null) navigation.navigate(route) { launchSingleTop = true }
                        }
                    )
                }

                composable<ScreenRoute.SettingsSubpage> { entry ->
                    SettingsHighlightEntry(settingsAnchor, onTaken = { settingsAnchor = null }) {
                        SettingsPageScreen(
                            page = SettingsPage.fromName(entry.toRoute<ScreenRoute.SettingsSubpage>().page),
                            onBack = { navigation.popBackStack() },
                            // «Alarme», «Erweitert» und «Über» öffnen weitere Unterseiten; Zurück führt wieder dorthin.
                            // Ohne launchSingleTop: gleiches Ziel (SettingsSubpage) würde sonst den
                            // Eintrag «Alarme» ersetzen statt darüber zu legen
                            onOpenPage = { page ->
                                if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    navigation.navigate(ScreenRoute.SettingsSubpage(page.name))
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

                composable<ScreenRoute.SettingsMarketAlerts> {
                    SettingsHighlightEntry(settingsAnchor, onTaken = { settingsAnchor = null }) {
                        MarketAlertsSettingsScreen(onBack = { navigation.popBackStack() })
                    }
                }

                composable<ScreenRoute.SettingsSpeech> {
                    SettingsHighlightEntry(settingsAnchor, onTaken = { settingsAnchor = null }) {
                        SpeechSettingsScreen(onBack = { navigation.popBackStack() })
                    }
                }

                composable<ScreenRoute.MarketPhase> { entry ->
                    // Aktionsblatt aus «Heute auffällig»: dieselbe Instanz wie die Merkliste (ihr Eintrag
                    // ist als Startziel immer im Stapel); zur Not eine eigene für diesen Tab
                    val watchlistOwner = androidx.compose.runtime.remember(entry) {
                        runCatching { navigation.getBackStackEntry<ScreenRoute.Watchlist>() }.getOrNull() ?: entry
                    }
                    // Erst beim ersten Öffnen erzeugt, danach bis zum Ende der Activity behalten
                    MarketPhaseScreen(
                        viewModel = hiltViewModel(viewModelStoreOwner = activityOwner),
                        watchlistViewModel = hiltViewModel(viewModelStoreOwner = watchlistOwner),
                        lockViewModel = lockViewModel,
                        jump = MarketJump.fromName(marketJump),
                        onJumpHandled = { marketJump = null },
                        onOpenAlarms = { watchId -> navigation.navigate(ScreenRoute.Alarms(watchId)) },
                        // Seite «Paar hinzufügen» über dem Markt-Tab (G-13): zurück führt wieder
                        // hierher, nicht in die Merkliste (gleich wie iOS)
                        onOpenExplorer = { query ->
                            explorerSearch = query
                            navigation.openExplorer()
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

                // G-10: «Einordnung» bzw. «Daten» als eigene Seite über dem Markt-Tab (ohne Tableiste);
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

                composable<ScreenRoute.Portfolio> {
                    PortfolioGate(portfolioAccess, requestUnlock, lockViewModel) {
                        PortfolioScreen(
                            onOpenCoin = { coin -> navigation.navigate(ScreenRoute.PortfolioCoin(coin)) },
                            onOpenAbout = {
                                navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
                            }
                        )
                    }
                }

                composable<ScreenRoute.PortfolioCoin> { entry ->
                    PortfolioGate(portfolioAccess, requestUnlock, lockViewModel) {
                        PortfolioDetailScreen(
                            coin = entry.toRoute<ScreenRoute.PortfolioCoin>().coin,
                            onBack = { navigation.popBackStack() }
                        )
                    }
                }

                // AlarmsViewModel liest die Watch-Id selbst aus dem SavedStateHandle (ScreenRoute.AlarmsArgWatchId)
                composable<ScreenRoute.Alarms> {
                    AlarmsScreen(onBack = { navigation.popBackStack() })
                }

                composable<ScreenRoute.AlarmsOverview> {
                    AlarmsOverviewScreen(
                        onBack = { navigation.popBackStack() },
                        onOpenWatch = { watchId -> navigation.navigate(ScreenRoute.Alarms(watchId)) },
                        onOpenLog = openAlarmLog,
                        onOpenDelivery = openAlarmDelivery,
                    )
                }

                composable<ScreenRoute.AlarmLog> {
                    AlarmLogScreen(onBack = { navigation.popBackStack() })
                }

                composable<ScreenRoute.AlarmDelivery> {
                    AlarmDeliveryScreen(
                        onBack = { navigation.popBackStack() },
                        onOpenAlarmSettings = {
                            navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.ALARMS.name)) { launchSingleTop = true }
                        },
                        onOpenUpdateSettings = openUpdateSettings,
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

/**
 * Inhalt des Portfolios nur frei; gesperrt der ruhige Sperr-Zustand (ohne Displaysperre mit Hinweis und «Sperre
 * ausschalten»), beim Kaltstart leer.
 */
@Composable
private fun PortfolioGate(
    access: PortfolioAccess,
    onUnlock: () -> Unit,
    lockViewModel: PortfolioLockViewModel,
    content: @Composable () -> Unit,
) {
    when (access) {
        PortfolioAccess.OPEN -> content()
        PortfolioAccess.LOCKED -> {
            val screenLockMissing by lockViewModel.screenLockMissing.collectAsStateWithLifecycle()
            PortfolioLockedState(
                onUnlock = onUnlock,
                screenLockMissing = screenLockMissing,
                onTurnOffLock = lockViewModel::turnOffLockWithoutScreenLock,
            )
        }
        PortfolioAccess.PENDING -> PortfolioPendingState()
    }
}

/** Seite «Paar hinzufügen» über dem aktuellen Tab (eigene Seite mit Zurück, ohne Tableiste). */
private fun NavHostController.openExplorer() {
    navigate(ScreenRoute.Explorer) { launchSingleTop = true }
}

private fun NavHostController.navigateToTab(route: ScreenRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
