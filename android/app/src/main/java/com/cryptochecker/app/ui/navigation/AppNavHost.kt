package com.cryptochecker.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.about.BatteryOptimizationDialog
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmDelivery
import com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmLog
import com.cryptochecker.app.ui.features.explorer.rememberExplorerSections
import com.cryptochecker.app.ui.features.info.MarketJump
import com.cryptochecker.app.ui.features.portfolio.LocalHidePortfolioAmounts
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.lock.HideOverlaysEffect
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.lock.SecureWindowEffect

/** Überblendung beim Wechsel von Tab oder Seite (ms). */
private const val NAV_FADE_MILLIS = 200

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
                onMarketJump = { marketJump = it },
                onWhyWatch = { whyWatchId = it },
                onFocusWatch = { focusWatchId = it },
            )
        }
        onOpenTargetHandled()
    }

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
                enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(NAV_FADE_MILLIS)) },
                exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(NAV_FADE_MILLIS)) },
            ) {
                watchlistDestination(
                    navigation = navigation,
                    whyWatchId = { whyWatchId },
                    onWhyHandled = { whyWatchId = null },
                    focusWatchId = { focusWatchId },
                    onFocusHandled = { focusWatchId = null },
                )

                explorerDestination(
                    navigation = navigation,
                    search = { explorerSearch },
                    onSearchHandled = { explorerSearch = null },
                    sections = explorerSections,
                )

                settingsDestinations(
                    navigation = navigation,
                    anchor = { settingsAnchor },
                    onAnchorChange = { settingsAnchor = it },
                )

                marketDestinations(
                    navigation = navigation,
                    activityOwner = activityOwner,
                    marketJump = { marketJump },
                    onMarketJumpChange = { marketJump = it },
                    onExplorerSearch = { explorerSearch = it },
                )

                portfolioDestinations(
                    navigation = navigation,
                    portfolioAccess = { portfolioAccess },
                    requestUnlock = requestUnlock,
                    lockViewModel = lockViewModel,
                )

                pairDestination(navigation = navigation, activityOwner = activityOwner, lockViewModel = lockViewModel)

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
