package com.cryptochecker.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
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
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
import com.cryptochecker.app.ui.features.alarms.AlarmsOverviewScreen
import com.cryptochecker.app.ui.features.alarms.AlarmsScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerScreen
import com.cryptochecker.app.ui.features.explorer.ExplorerViewModel
import com.cryptochecker.app.ui.features.info.MarketPhaseScreen
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

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val iconRes: Int,
)

/**
 * Merkliste · Markt · (Portfolio) · Einstellungen (Runde 31: ohne Tab «Suchen»).
 * Der Portfolio-Tab erscheint nur, wenn er in den Optionen eingeschaltet ist. Paare sucht und
 * fügt man auf einer eigenen Seite hinzu ([ScreenRoute.Explorer]), geöffnet mit «+» der Merkliste.
 */
private fun buildBottomTabs(portfolioEnabled: Boolean) = buildList {
    add(BottomTab(ScreenRoute.Watchlist, R.string.tab_watchlist, R.drawable.ic_tab_watchlist))
    add(BottomTab(ScreenRoute.MarketPhase, R.string.tab_market_phase, R.drawable.ic_tab_market))
    if (portfolioEnabled) add(BottomTab(ScreenRoute.Portfolio, R.string.portfolio_title, R.drawable.ic_portfolio))
    add(BottomTab(ScreenRoute.Settings, R.string.settings_title, R.drawable.ic_settings))
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
    // «Warum?» aus einem Alarm: Paar, dessen «Warum bewegt sich das?» die Merkliste öffnen soll
    var whyWatchId by androidx.compose.runtime.saveable.rememberSaveable {
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
                }
                "alarms" -> navigation.navigate(ScreenRoute.AlarmsOverview) { launchSingleTop = true }
                // Portfolio-Widget: nur wenn der Tab eingeschaltet ist, sonst bleibt die Merkliste
                "portfolio" -> if (portfolioEnabled) navigation.navigateToTab(ScreenRoute.Portfolio) else Unit
                else -> com.cryptochecker.app.ui.MainActivity.whyWatchId(openTarget)?.let { id ->
                    whyWatchId = id
                    navigation.navigateToTab(ScreenRoute.Watchlist)
                }
            }
        }
        onOpenTargetHandled()
    }

    // Keine Begrüßung mehr (Runde 32): ein neuer Nutzer landet direkt in der Starter-Auswahl der
    // leeren Merkliste; «Was die App kann» steht unter Einstellungen › Über. Der Akku-Hinweis
    // kommt erst nach dem ersten Alarm, zurück auf einem Haupt-Tab (nicht über der Alarm-Bestätigung).
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
                            label = { TabLabel(stringResource(tab.labelRes)) },
                            // Gewählt: Icon und Name in der Themenfarbe, ohne Hintergrund-Pille
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // Tab- und Seitenwechsel: kurze Überblendung (statt der 700 ms von Navigation);
        // ohne Bewegung (Animationen aus) sofort
        val reduceMotion = rememberReduceMotion()
        NavHost(
            navController = navigation,
            startDestination = ScreenRoute.Watchlist,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(NAV_FADE_MILLIS)) },
            exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(NAV_FADE_MILLIS)) },
        ) {
            composable(ScreenRoute.Watchlist) {
                WatchlistScreen(
                    openWhyWatchId = whyWatchId,
                    onOpenWhyHandled = { whyWatchId = null },
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
                    onOpenMarketAlerts = { navigation.navigate(ScreenRoute.SettingsMarketAlerts) { launchSingleTop = true } },
                    onOpenSpeech = { navigation.navigate(ScreenRoute.SettingsSpeech) { launchSingleTop = true } },
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
                        onBack = { navigation.popBackStack() }
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
                    // Wie «+»: Seite «Paar hinzufügen» über der Merkliste (gleich wie iOS)
                    onOpenExplorer = { query ->
                        explorerSearch = query
                        navigation.navigateToTab(ScreenRoute.Watchlist)
                        navigation.openExplorer()
                    },
                    onOpenAbout = {
                        navigation.navigate(ScreenRoute.settingsPage(SettingsPage.ABOUT.name)) { launchSingleTop = true }
                    }
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
