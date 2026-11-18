package com.cryptochecker.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cryptochecker.app.domain.lock.PortfolioAccess
import com.cryptochecker.app.domain.lock.PortfolioLockPolicy
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmDelivery
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmLog
import com.cryptochecker.app.ui.features.explorer.ExplorerSectionsState
import com.cryptochecker.app.ui.features.explorer.rememberExplorerSections
import com.cryptochecker.app.ui.features.info.MarketJump
import com.cryptochecker.app.ui.features.portfolio.LocalHidePortfolioAmounts
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.lock.HideOverlaysEffect
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.lock.SecureWindowEffect
import com.cryptochecker.app.ui.theme.rememberReduceMotion
import com.cryptochecker.resources.R

// consumeWindowInsets war in älteren Compose-Versionen experimentell
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val currentRoute = currentDestination?.route // Muster der Route: nur Schlüssel der Effekte unten (Vergleich typsicher)
    // Unterseiten des Portfolios (Coin-Detail, Steuer-Arbeitsunterlage): gesperrt zurück zum Tab
    val onPortfolioSubpage = currentDestination?.hasRoute<ScreenRoute.PortfolioCoin>() == true ||
        currentDestination?.hasRoute<ScreenRoute.PortfolioTaxWorksheet>() == true
    val onPortfolio = onPortfolioSubpage || currentDestination?.hasRoute<ScreenRoute.Portfolio>() == true
    val portfolioEnabled by navViewModel.portfolioEnabled.collectAsStateWithLifecycle()
    val bottomTabs = remember(portfolioEnabled) { buildBottomTabs(portfolioEnabled) }
    // Gewählter Tab; null auf Seiten ohne Tableiste (Alarme, Unterseiten, «Paar hinzufügen»)
    val currentTab = bottomTabs.firstOrNull { tab -> currentDestination?.hasRoute(tab.route::class) == true }?.route
    // Breites Fenster: Startseite des Tabs links ([listPaneRoot]); die Tableiste zeigt dann ihren Tab
    val wide = rememberTwoPane()
    val listRoot = remember(backStackEntry, wide) { listPaneRoot(navigation, currentDestination, wide) }
    val barTab = currentTab ?: listRoot?.let { root -> bottomTabs.firstOrNull { root.destination.hasRoute(it.route::class) }?.route }
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
    // Portfolio sichtbar: keine fremden Fenster darüber (ab Android 12)
    HideOverlaysEffect(onPortfolio)
    // Besitzer ausserhalb des Navigationsgraphen (die Activity): Der Markt-Tab behält
    // so sein ViewModel samt geladener Daten über Tab-Wechsel hinweg.
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current) { "Kein ViewModelStoreOwner" }
    // «Was bewegt sich mit?» aus einem Alarm: Paar, dessen Blatt die Merkliste öffnen soll
    var whyWatchId by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<Long?>(null)
    }
    // Tipp auf Mitteilung bzw. Widget-Zeile: Paar, dessen Seite die Merkliste öffnen soll
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

    AppNavEffects(
        navigation = navigation,
        portfolioAccess = portfolioAccess,
        portfolioEnabled = portfolioEnabled,
        currentRoute = currentRoute,
        onPortfolioSubpage = onPortfolioSubpage,
        onPortfolio = onPortfolio,
        openTarget = openTarget,
        onOpenTargetHandled = onOpenTargetHandled,
        onMarketJump = { marketJump = it },
        onWhyWatch = { whyWatchId = it },
        onFocusWatch = { focusWatchId = it },
    )

    // Keine Begrüssung: ein neuer Nutzer landet direkt in der Starter-Auswahl der
    // leeren Merkliste; «Was die App kann» steht unter Einstellungen › Über die App. Der Akku-Hinweis
    // kommt erst nach dem ersten Alarm, zurück auf einem Haupt-Tab (nicht über der Alarm-Bestätigung).
    BatteryOptimizationDialog(calm = currentTab != null)

    // «Kurs-Aktualisierung ändern» auf «Damit Alarme ankommen» öffnet die Seite «Kurs-Aktualisierung» direkt
    val openUpdateSettings: () -> Unit = remember(navigation) {
        { navigation.navigate(ScreenRoute.SettingsSubpage(SettingsPage.UPDATES.name)) { launchSingleTop = true } }
    }
    // Alarm-Protokoll: auch aus den Einstellungen («Kurs-Aktualisierung», neben «Letzte Prüfungen»)
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
        LocalHidePortfolioAmounts provides navViewModel.hidePortfolioAmounts.collectAsStateWithLifecycle().value,
        LocalOpenAlarmLog provides openAlarmLog,
        LocalOpenAlarmDelivery provides openAlarmDelivery,
        LocalTabReselect provides tabReselect,
    ) {
        Scaffold(
            // testTags als Ressourcen-Id für alle Seiten (Tableiste, Paarseite): Baseline-Profile-Generator (UiAutomator)
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            bottomBar = {
                AppBottomBar(currentTab = barTab, tabs = bottomTabs, navigation = navigation, tabReselect = tabReselect)
            }
        ) { innerPadding ->
            AppNavGraph(
                innerPadding = innerPadding,
                navigation = navigation,
                listRoot = listRoot,
                activityOwner = activityOwner,
                whyWatchId = { whyWatchId },
                onWhyHandled = { whyWatchId = null },
                focusWatchId = { focusWatchId },
                onFocusHandled = { focusWatchId = null },
                explorerSearch = { explorerSearch },
                onExplorerSearchChange = { explorerSearch = it },
                explorerSections = explorerSections,
                settingsAnchor = { settingsAnchor },
                onSettingsAnchorChange = { settingsAnchor = it },
                marketJump = { marketJump },
                onMarketJumpChange = { marketJump = it },
                portfolioAccess = { portfolioAccess },
                requestUnlock = requestUnlock,
                lockViewModel = lockViewModel,
                openAlarmLog = openAlarmLog,
                openAlarmDelivery = openAlarmDelivery,
                openUpdateSettings = openUpdateSettings,
            )
        }
    }
}

/**
 * Graph der App unter der Tableiste ([AppNavHost]): alle Tabs und Seiten mit kurzer Überblendung. Nicht neu startbar:
 * läuft wie zuvor mit dem Inhalt des Gerüsts.
 */
@NonRestartableComposable
@Composable
private fun AppNavGraph(
    innerPadding: PaddingValues,
    navigation: NavHostController,
    listRoot: NavBackStackEntry?,
    activityOwner: ViewModelStoreOwner,
    whyWatchId: () -> Long?,
    onWhyHandled: () -> Unit,
    focusWatchId: () -> Long?,
    onFocusHandled: () -> Unit,
    explorerSearch: () -> String?,
    onExplorerSearchChange: (String?) -> Unit,
    explorerSections: ExplorerSectionsState,
    settingsAnchor: () -> String?,
    onSettingsAnchorChange: (String?) -> Unit,
    marketJump: () -> String?,
    onMarketJumpChange: (String?) -> Unit,
    portfolioAccess: () -> PortfolioAccess,
    requestUnlock: () -> Unit,
    lockViewModel: PortfolioLockViewModel,
    openAlarmLog: () -> Unit,
    openAlarmDelivery: () -> Unit,
    openUpdateSettings: () -> Unit,
) {
    // Tab- und Seitenwechsel: kurze Überblendung (statt der 700 ms von Navigation);
    // ohne Bewegung (Animationen aus) sofort
    val reduceMotion = rememberReduceMotion()
    val args = remember(navigation, activityOwner, lockViewModel) {
        TabRootArgs(
            navigation = navigation, activityOwner = activityOwner, whyWatchId = whyWatchId, onWhyHandled = onWhyHandled,
            focusWatchId = focusWatchId, onFocusHandled = onFocusHandled, marketJump = marketJump,
            onMarketJumpChange = onMarketJumpChange, onExplorerSearch = { onExplorerSearchChange(it) },
            portfolioAccess = portfolioAccess, requestUnlock = requestUnlock, lockViewModel = lockViewModel,
            onSettingsAnchorChange = onSettingsAnchorChange,
        )
    }
    // Breit: Startseite links (eigene Spalte ausserhalb des Graphen), der Graph rechts; schmal nur der Graph
    Row(
        // Tastatur: Die Tableiste bleibt unten und verschwindet hinter der Tastatur
        // (adjustResize, randlos – das Fenster wird weder verschoben noch verkleinert). Der Inhalt
        // bekommt nur den Teil der Tastatur, der über die Tableiste hinausragt, als Abstand –
        // so bleibt das fokussierte Feld sichtbar. Danach gelten die Ränder dieses Scaffolds
        // (Statusleiste oben, Tableiste bzw. Navigationsleiste unten) als verbraucht: Die Seiten
        // mit eigenem Scaffold/TopAppBar setzen sie sonst ein zweites Mal (doppelter Abstand oben).
        modifier = Modifier
            .padding(innerPadding)
            .windowInsetsPadding(
                WindowInsets.ime.exclude(WindowInsets(bottom = innerPadding.calculateBottomPadding()))
            )
            .consumeWindowInsets(innerPadding),
    ) {
        if (listRoot != null) ListPane(listRoot, args)
        CompositionLocalProvider(LocalTwoPane provides (listRoot != null)) {
            AppNavHostGraph(
                navigation, reduceMotion, Modifier.weight(1f), args, explorerSearch, onExplorerSearchChange,
                explorerSections, settingsAnchor, onSettingsAnchorChange, activityOwner, portfolioAccess, requestUnlock,
                lockViewModel, openAlarmLog, openAlarmDelivery, openUpdateSettings
            )
        }
    }
}

/** Gesperrt bzw. ausgeschaltet zurück, App-Verknüpfung öffnen ([AppNavHost]). */
@Composable
private fun AppNavEffects(
    navigation: NavHostController,
    portfolioAccess: PortfolioAccess,
    portfolioEnabled: Boolean,
    currentRoute: String?,
    onPortfolioSubpage: Boolean,
    onPortfolio: Boolean,
    openTarget: String?,
    onOpenTargetHandled: () -> Unit,
    onMarketJump: (String) -> Unit,
    onWhyWatch: (Long) -> Unit,
    onFocusWatch: (Long) -> Unit,
) {
    // Gesperrt, während eine Unterseite des Portfolios offen ist: zurück zum (gesperrten) Portfolio-Tab
    androidx.compose.runtime.LaunchedEffect(portfolioAccess, currentRoute) {
        if (portfolioAccess == PortfolioAccess.LOCKED && onPortfolioSubpage) {
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
                onMarketJump = onMarketJump,
                onWhyWatch = onWhyWatch,
                onFocusWatch = onFocusWatch,
            )
        }
        onOpenTargetHandled()
    }
}

/** Tableiste ([AppNavHost]) nur auf Haupt-Tabs; erneutes Tippen schliesst eine Unterseite und rollt an den Anfang. */
@Composable
private fun AppBottomBar(
    currentTab: ScreenRoute?,
    tabs: List<BottomTab>,
    navigation: NavHostController,
    tabReselect: TabReselectSignal,
) {
    if (currentTab != null) {
        AppTabBar(
            tabs = tabs,
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
