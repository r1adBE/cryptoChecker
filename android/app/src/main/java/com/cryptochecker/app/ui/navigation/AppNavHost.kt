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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.market.MarketSections
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
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
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.features.watchlist.WatchlistScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
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
            openAppTarget(
                navigation = navigation,
                openTarget = openTarget,
                portfolioEnabled = portfolioEnabled,
                onMarketJump = { marketJump = it },
                onWhyWatch = { whyWatchId = it },
                onFocusWatch = { focusWatchId = it },
            )
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
                composable<ScreenRoute.Watchlist> { entry ->
                    WatchlistScreen(
                        openWhyWatchId = whyWatchId,
                        onOpenWhyHandled = { whyWatchId = null },
                        focusWatchId = focusWatchId,
                        onFocusHandled = { focusWatchId = null },
                        // Doppel-Tipp (Glocke, «Ersten Alarm setzen») legt die Seite nur einmal auf den Stapel
                        onAddClick = { navigation.openExplorerOnce(entry) },
                        onOpenAlarms = { watchId -> navigation.navigateOnce(entry, ScreenRoute.Alarms(watchId)) },
                        onOpenAllAlarms = { navigation.navigateOnce(entry, ScreenRoute.AlarmsOverview) },
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

                settingsDestinations(
                    navigation = navigation,
                    anchor = { settingsAnchor },
                    onAnchorChange = { settingsAnchor = it },
                )

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
                        onOpenAlarms = { watchId -> navigation.navigateOnce(entry, ScreenRoute.Alarms(watchId)) },
                        // Seite «Paar hinzufügen» über dem Markt-Tab (G-13): zurück führt wieder
                        // hierher, nicht in die Merkliste (gleich wie iOS)
                        onOpenExplorer = { query ->
                            explorerSearch = query
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
                    // Dieselbe PortfolioViewModel-Instanz wie der Tab (keine doppelten Abrufe); fehlt
                    // dessen Eintrag im Stapel (heute nie, nur aus dem Tab erreichbar), zur Not eine eigene
                    val portfolioOwner = androidx.compose.runtime.remember(entry) {
                        runCatching { navigation.getBackStackEntry<ScreenRoute.Portfolio>() }.getOrNull() ?: entry
                    }
                    PortfolioGate(portfolioAccess, requestUnlock, lockViewModel) {
                        PortfolioDetailScreen(
                            coin = entry.toRoute<ScreenRoute.PortfolioCoin>().coin,
                            onBack = { navigation.popBackStack() },
                            viewModel = hiltViewModel(viewModelStoreOwner = portfolioOwner),
                        )
                    }
                }

                alarmDestinations(
                    navigation = navigation,
                    openAlarmLog = openAlarmLog,
                    openAlarmDelivery = openAlarmDelivery,
                    openUpdateSettings = openUpdateSettings,
                )
            }
        }
    }
}
