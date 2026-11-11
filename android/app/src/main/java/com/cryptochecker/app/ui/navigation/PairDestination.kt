// Ziel «Paarseite» des Navigationsgraphen (Teil von `AppNavHost`): sucht die ViewModels zusammen (Merkliste für die
// Aktionen, eigenes für die Alarme, Markt für die Vorschau) und baut die Vorschau eines Coins aus dem Markt-Tab auf.
// Eigene Datei, weil `AppNavHost` sonst zu lang würde.
package com.cryptochecker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.ui.features.info.InfoViewModel
import com.cryptochecker.app.ui.features.info.MARKET_COIN_UI_EVENTS
import com.cryptochecker.app.ui.features.info.previewQuote
import com.cryptochecker.app.ui.features.info.previewWatch
import com.cryptochecker.app.ui.features.watchlist.PairPreview
import com.cryptochecker.app.ui.features.watchlist.PairScreen
import com.cryptochecker.app.ui.features.watchlist.WATCHLIST_UI_EVENTS
import com.cryptochecker.app.ui.features.watchlist.WatchUiEventsHolder
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel

/**
 * Paarseite über Merkliste bzw. Markt-Tab. Aktionen über die Instanz der Merkliste (ihr Eintrag ist als Startziel immer
 * im Stapel), Alarme über ein `AlarmsViewModel` dieses Eintrags (liest `watchId`). Das Banner mit «Rückgängig» nach
 * «Löschen» erscheint dort, woher die Seite kam ([WatchUiEventsHolder] jenes Eintrags). Vorschau: Markt-ViewModel der
 * Activity ([activityOwner]); nach dem Hinzufügen ersetzt die Seite des neuen Eintrags die Vorschau.
 */
internal fun NavGraphBuilder.pairDestination(
    navigation: NavHostController,
    activityOwner: ViewModelStoreOwner,
    lockViewModel: PortfolioLockViewModel,
) {
    composable<ScreenRoute.PairPage> { entry ->
        val route = entry.toRoute<ScreenRoute.PairPage>()
        val watchlistOwner = remember(entry) {
            runCatching { navigation.getBackStackEntry<ScreenRoute.Watchlist>() }.getOrNull() ?: entry
        }
        val origin = remember(entry) { navigation.previousBackStackEntry ?: entry }
        val originKey = if (origin.destination.hasRoute<ScreenRoute.MarketPhase>()) MARKET_COIN_UI_EVENTS else WATCHLIST_UI_EVENTS
        val originEvents: WatchUiEventsHolder = viewModel(viewModelStoreOwner = origin, key = originKey) { WatchUiEventsHolder() }
        // Gelöscht wird im SavedStateHandle (übersteht Prozess-Neustart): nur einmal fragen
        val askNotifications by entry.savedStateHandle
            .getStateFlow(ScreenRoute.PairPageArgAskNotifications, route.askNotifications)
            .collectAsStateWithLifecycle()
        val preview = route.preview?.let { symbol ->
            rememberPairPreview(
                symbol = symbol,
                info = hiltViewModel(viewModelStoreOwner = activityOwner),
                // Binance führt das Paar nicht mehr: zurück
                onGone = { navigation.popBackStack() },
                openStored = { id, ask ->
                    if (entry.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        navigation.navigate(ScreenRoute.PairPage(id, askNotifications = ask)) {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    }
                },
            )
        }
        PairScreen(
            watchId = route.watchId,
            viewModel = hiltViewModel(viewModelStoreOwner = watchlistOwner),
            alarms = hiltViewModel(),
            lockViewModel = lockViewModel,
            originEvents = originEvents,
            onBack = { navigation.popBackStack() },
            preview = preview,
            askNotifications = askNotifications,
            onAskNotificationsHandled = { entry.savedStateHandle[ScreenRoute.PairPageArgAskNotifications] = false },
        )
    }
}

/** Vorschau-Paar zu [symbol] mit Kurs (eine Ticker-Abfrage, nichts gespeichert); «Erneut versuchen» lädt neu. */
@Composable
private fun rememberPairPreview(
    symbol: String,
    info: InfoViewModel,
    onGone: () -> Unit,
    openStored: (Long, Boolean) -> Unit,
): PairPreview {
    var base by remember(symbol) { mutableStateOf<WatchEntity?>(null) }
    LaunchedEffect(symbol) {
        val built = info.previewWatch(symbol)
        if (built == null) onGone() else base = built
    }
    var reload by remember(symbol) { mutableIntStateOf(0) }
    val shown by produceState<WatchEntity?>(null, base, reload) {
        val start = base ?: return@produceState
        // Älterer Kurs bleibt bei einem Fehler stehen (die Seite bietet dann «Erneut versuchen» an)
        value = info.previewQuote(value ?: start)
    }
    return PairPreview(watch = shown ?: base, reload = { reload++ }, openStored = openStored)
}
