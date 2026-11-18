// Der Navigationsgraph mit allen Zielen (Teil von `AppNavHost`, eigene Datei wegen der Länge).
package com.cryptochecker.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.cryptochecker.app.domain.lock.PortfolioAccess
import com.cryptochecker.app.ui.features.explorer.ExplorerSectionsState
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel

/** Überblendung beim Wechsel von Tab oder Seite (ms). */
private const val NAV_FADE_MILLIS = 200

/** Der Graph selbst ([AppNavGraph]): alle Ziele mit kurzer Überblendung. */
@Composable
internal fun AppNavHostGraph(
    navigation: NavHostController,
    reduceMotion: Boolean,
    modifier: Modifier,
    args: TabRootArgs,
    explorerSearch: () -> String?,
    onExplorerSearchChange: (String?) -> Unit,
    explorerSections: ExplorerSectionsState,
    settingsAnchor: () -> String?,
    onSettingsAnchorChange: (String?) -> Unit,
    activityOwner: ViewModelStoreOwner,
    portfolioAccess: () -> PortfolioAccess,
    requestUnlock: () -> Unit,
    lockViewModel: PortfolioLockViewModel,
    openAlarmLog: () -> Unit,
    openAlarmDelivery: () -> Unit,
    openUpdateSettings: () -> Unit,
) {
    NavHost(
        navController = navigation,
        startDestination = ScreenRoute.Watchlist,
        modifier = modifier,
        enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(NAV_FADE_MILLIS)) },
        exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(NAV_FADE_MILLIS)) },
    ) {
        watchlistDestination(args)

        explorerDestination(
            navigation = navigation,
            search = explorerSearch,
            onSearchHandled = { onExplorerSearchChange(null) },
            sections = explorerSections,
        )

        settingsDestinations(
            navigation = navigation,
            anchor = settingsAnchor,
            onAnchorChange = onSettingsAnchorChange,
            args = args,
        )

        marketDestinations(navigation = navigation, activityOwner = activityOwner, args = args)

        portfolioDestinations(
            navigation = navigation,
            portfolioAccess = portfolioAccess,
            requestUnlock = requestUnlock,
            lockViewModel = lockViewModel,
            args = args,
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
