package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.res.Resources
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.starter.AddMoment
import androidx.lifecycle.compose.LifecycleStartEffect
import com.cryptochecker.app.domain.refresh.RefreshDebounce
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.ui.components.RowHighlight
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.util.StartupClock
import com.cryptochecker.resources.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Teile von [WatchlistScreen]: Sichtbarkeit und Startmessung, «nach unten ziehen», Hervorhebung der Zeilen.

/**
 * Live-Kurse nur, solange die Merkliste zu sehen ist; Tage mit offener App zählen (erster Gebrauch,
 * [com.cryptochecker.app.domain.watch.WatchlistOnboarding]); App-Start messen: erstes Bild der Merkliste aus dem
 * Zwischenspeicher (nur lokal, Bericht «Ablauf»).
 */
@Composable
internal fun WatchlistVisibleEffects(viewModel: WatchlistViewModel, loaded: Boolean) {
    LifecycleStartEffect(Unit) {
        viewModel.setLiveVisible(true)
        viewModel.recordUsageDay()
        onStopOrDispose { viewModel.setLiveVisible(false) }
    }
    ReportDrawnWhen { loaded }
    LaunchedEffect(loaded) {
        if (loaded) {
            withFrameNanos { }
            StartupClock.onFirstFrame()?.let(viewModel::recordAppStart)
        }
    }
}

/** «Alles aktualisieren» ([request]) und ob der Kreisel nur zum Zeigen dreht ([echo]). */
internal class PullRefresh(val request: () -> Unit, private val echoState: androidx.compose.runtime.State<Boolean>) {
    val echo: Boolean get() = echoState.value
}

/**
 * Alles aktualisieren (nach unten ziehen, Menü). Eben erst aktualisiert (unter 15 s): kein neuer Durchlauf — der
 * Menüpunkt ist dann grau mit Countdown ([WatchlistMenu]); wer zieht, sieht den Kreisel kurz drehen und dann «Gerade
 * aktualisiert · vor N s» (die Sperre bleibt intern, [RefreshDebounce.pullEchoSeconds]).
 */
@Composable
internal fun rememberPullRefresh(
    viewModel: WatchlistViewModel,
    scope: CoroutineScope,
    banner: WatchlistBanner,
    resources: Resources,
): PullRefresh {
    val echo = remember { mutableStateOf(false) }
    val request: () -> Unit = {
        val decision = viewModel.refreshAllByUser()
        val ago = RefreshDebounce.pullEchoSeconds(decision, viewModel.lastFullRefreshAt(), System.currentTimeMillis())
        if (ago != null && !echo.value) scope.launch {
            echo.value = true
            delay(RefreshDebounce.PULL_ECHO_MILLIS)
            echo.value = false
            banner.show(resources.getString(R.string.watchlist_refresh_just_done, ago))
        }
    }
    return PullRefresh(request, echo)
}

/** ★/⚡ in den Zeilen nur farbig, solange der passende Chip gewählt ist; ebenso das Art-Symbol (Token, TradFi, α). */
internal fun rowHighlightFor(quickView: QuickView?, selectedGroup: String?): RowHighlight = RowHighlight(
    favorites = quickView == null && WatchFilter.isFavorites(selectedGroup),
    activity = quickView == QuickView.ACTIVITY,
    tokens = quickView == null && WatchFilter.isTokens(selectedGroup),
    tradFi = quickView == null && selectedGroup == AutoGroup.TRADFI,
    alpha = quickView == null && WatchFilter.isAlpha(selectedGroup),
)

/** Erst-Moment ([moment]) und [askAfterHint]: nach übernommener Start-Auswahl erst kurz erklären, dann fragen. */
internal class StarterMoment(val moment: AddMoment?, val askAfterHint: () -> Unit)

/**
 * Erst-Moment (Start-Merkliste, allererstes Paar, [rememberAddMoment]). Start-Auswahl: neue Paare haben
 * Kurs-Mitteilungen an — Erlaubnis fragen, aber erst, wenn das Banner «… wird jetzt überwacht» mit «Alarm setzen» weg
 * ist (abgelaufen oder weggewischt) — sonst verdeckt die Erklärung genau diesen ersten Schritt. Mit «Alarm setzen»
 * getippt fragt das Speichern des Alarms selbst (gleich wie iOS).
 */
@Composable
internal fun rememberStarterMoment(
    viewModel: WatchlistViewModel,
    watches: List<WatchEntity>,
    loaded: Boolean,
    alarmCounts: Map<Long, Int>,
    banner: WatchlistBanner,
    onOpenAlarms: (Long) -> Unit,
): StarterMoment {
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest(
        com.cryptochecker.app.ui.components.NotificationAsk.PAIRS_ADDED
    )
    var askNotificationsAfterHint by rememberSaveable { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val moment = rememberAddMoment(viewModel, watches, loaded, alarmCounts, banner, onOpenAlarms) { alarmTapped ->
        if (askNotificationsAfterHint) {
            askNotificationsAfterHint = false
            // Banner nur weg, weil die Merkliste verlassen wird («+», App zu): nicht fragen — sonst stünde die
            // Erklärung über dem nächsten Bildschirm (Explorer) bzw. ginge bei der Zurück-Geste wieder auf
            val onScreen = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!alarmTapped && onScreen) requestNotifications()
        }
    }
    return StarterMoment(moment) { askNotificationsAfterHint = true }
}

/** Mehrfachauswahl: nur Paare, die es noch gibt und die zu sehen sind. */
internal fun visibleSelection(selected: Set<Long>, visible: List<WatchEntity>): Set<Long> {
    val ids = visible.mapTo(HashSet()) { it.id }
    return selected.filterTo(HashSet()) { it in ids }
}

/** Live-Kurse für die gezeigten Paare (auch in der ⚡-Ansicht über alle Gruppen); deaktivierte nie. */
@Composable
internal fun WatchlistLivePairs(viewModel: WatchlistViewModel, visible: List<WatchEntity>) {
    val livePairs = remember(visible) { DisabledView.active(visible) { it.disabled }.map { it.toLivePair() } }
    LaunchedEffect(livePairs) { viewModel.setLivePairs(livePairs) }
}

/**
 * Noch nicht geladen: Gerüst der Liste; leere Merkliste: Start-Auswahl der grössten Coins ([StarterUiState]).
 * true = gezeigt (die Liste entfällt). [paddingModifier]: Abstand des Scaffolds.
 */
@Composable
internal fun showsNotListed(
    loaded: Boolean,
    empty: Boolean,
    paddingModifier: Modifier,
    starter: StarterUiState,
    viewModel: WatchlistViewModel,
    onAddClick: () -> Unit,
    onOpenAlarms: (() -> Unit)?,
    onStartersAdded: () -> Unit,
): Boolean {
    if (!loaded) {
        SkeletonList(modifier = paddingModifier)
        return true
    }
    if (!empty) return false
    WatchlistStarterPicker(
        modifier = Modifier.fillMaxSize().then(paddingModifier),
        starter = starter,
        viewModel = viewModel,
        onAddClick = onAddClick,
        onOpenAlarms = onOpenAlarms,
        onStartersAdded = onStartersAdded,
    )
    return true
}
