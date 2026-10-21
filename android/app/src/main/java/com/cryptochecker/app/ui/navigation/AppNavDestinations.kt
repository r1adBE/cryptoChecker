// Ziele des Navigationsgraphen (Teil von `AppNavHost`): Einstellungen samt Unterseiten und die Alarm-Seiten, dazu die
// Hülle einer Einstellungs-Unterseite aus der Suche und die Sperre des Portfolios.
// Eigene Datei, weil `AppNavHost` sonst zu lang würde; Zustand (Anker der Suche) bleibt dort.
package com.cryptochecker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.ui.features.alarms.AlarmsOverviewScreen
import com.cryptochecker.app.ui.features.alarms.AlarmsScreen
import com.cryptochecker.app.ui.features.alarms.AlarmLogScreen
import com.cryptochecker.app.ui.features.alarms.AlarmDeliveryScreen
import com.cryptochecker.app.ui.features.settings.MarketAlertsSettingsScreen
import com.cryptochecker.app.ui.features.settings.ProvideSettingsHighlight
import com.cryptochecker.app.ui.features.settings.SettingsPage
import com.cryptochecker.app.ui.features.settings.SettingsPageScreen
import com.cryptochecker.app.ui.features.settings.SettingsScreen
import com.cryptochecker.app.ui.features.settings.SettingsSearchTarget
import com.cryptochecker.app.ui.features.settings.SpeechSettingsScreen
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.lock.PortfolioLockedState
import com.cryptochecker.app.ui.lock.PortfolioPendingState
import androidx.navigation.NavGraphBuilder

/**
 * Einstellungen mit Unterseiten, Markt-Meldungen und Sprachausgabe. [anchor]: Punkt aus der Suche, den die nächste
 * Unterseite kurz hervorhebt ([onAnchorChange] setzt bzw. leert ihn).
 */
internal fun NavGraphBuilder.settingsDestinations(
    navigation: NavHostController,
    anchor: () -> String?,
    onAnchorChange: (String?) -> Unit,
) {
    composable<ScreenRoute.Settings> {
        SettingsScreen(
            onOpenPage = { page ->
                navigation.navigate(ScreenRoute.SettingsSubpage(page.name)) { launchSingleTop = true }
            },
            onOpenSearchTarget = { target ->
                onAnchorChange(target.anchor)
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
        SettingsHighlightEntry(anchor(), onTaken = { onAnchorChange(null) }) {
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
        SettingsHighlightEntry(anchor(), onTaken = { onAnchorChange(null) }) {
            MarketAlertsSettingsScreen(onBack = { navigation.popBackStack() })
        }
    }

    composable<ScreenRoute.SettingsSpeech> {
        SettingsHighlightEntry(anchor(), onTaken = { onAnchorChange(null) }) {
            SpeechSettingsScreen(onBack = { navigation.popBackStack() })
        }
    }
}

/** Alarme eines Paars, «Alle Alarme», Alarm-Protokoll und «Damit Alarme ankommen». */
internal fun NavGraphBuilder.alarmDestinations(
    navigation: NavHostController,
    openAlarmLog: () -> Unit,
    openAlarmDelivery: () -> Unit,
    openUpdateSettings: () -> Unit,
) {
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
            onAddPair = { navigation.openExplorer() },
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
internal fun PortfolioGate(
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
