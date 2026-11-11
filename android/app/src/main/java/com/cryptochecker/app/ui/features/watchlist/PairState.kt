// Zustand der Paarseite neben dem ViewModel: offene Dialoge (übersteht Drehen und Prozess-Neustart), «Zur Merkliste
// hinzufügen» der Vorschau und die Rückmeldungen nach der Rückfrage «nicht gespeichert» (Banner, Haptik, Erlaubnis).
// Eigene Datei, damit `PairScreen` nur den Aufbau enthält.
package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.ui.components.CollectScreenEvents
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.NotificationAsk
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.domain.util.LocaleNumbers

/** Schlüssel der Paarseite für [rememberWatchUiEvents] (eigener Empfänger je Seite). */
internal const val PAIR_UI_EVENTS = "pair_ui_events"

/** Offene Blätter und Dialoge der Paarseite. */
internal data class PairDialogs(
    val why: Boolean = false,
    val editPair: Boolean = false,
    val note: Boolean = false,
    val group: Boolean = false,
    val widget: Boolean = false,
) {
    companion object {
        /** Als Liste von Wahrheitswerten (Bundle-tauglich). */
        val saver: Saver<PairDialogs, List<Boolean>> = Saver(
            save = { arrayListOf(it.why, it.editPair, it.note, it.group, it.widget) },
            restore = { PairDialogs(it[0], it[1], it[2], it[3], it[4]) },
        )
    }
}

/** «Zur Merkliste hinzufügen» der Vorschau: Id des neuen Eintrags (übersteht Drehen) und Schutz vor Doppeltipp. */
@Stable
internal class PairAddState(addedIdState: androidx.compose.runtime.MutableState<Long?>) {
    /** Hinzugefügt: Id des neuen Eintrags (die Seite wechselt zu ihm, sobald er einen Kurs hat). */
    var addedId by addedIdState

    /** Läuft gerade (Rückfrage «nicht gespeichert» offen bzw. Schreiben); nur gegen Doppeltipp, ohne Anzeige. */
    var pending by mutableStateOf(false)

    /** Wie die Seite «Paar hinzufügen»: Voll bzw. hinzugefügt kommt über Ereignisse ([PairUiEffects]). */
    fun add(viewModel: WatchlistViewModel, events: WatchUiEventsHolder, candidate: WatchEntity?) {
        if (candidate == null || addedId != null || pending) return
        pending = true
        viewModel.addPreview(candidate, { events.send(WatchUiEvent.PreviewFull) }) { id, added ->
            events.send(WatchUiEvent.PreviewAdded(id, added))
        }
    }
}

@Composable
internal fun rememberPairAddState(): PairAddState {
    val addedId = rememberSaveable { mutableStateOf<Long?>(null) }
    return remember { PairAddState(addedId) }
}

/**
 * Rückmeldungen der Paarseite im GERADE gezeigten Bildschirm (auch nach dem Neuerstellen der Activity): Banner und
 * Haptik, Erlaubnis für Mitteilungen, Vorschau voll bzw. hinzugefügt. [askNotifications]: Seite eben aus der Vorschau
 * entstanden — einmal nach der Erlaubnis fragen (erst kurz erklären, dann die System-Abfrage).
 */
@Composable
internal fun PairUiEffects(
    holder: WatchUiEventsHolder,
    viewModel: WatchlistViewModel,
    banner: WatchlistBanner,
    addState: PairAddState,
    preview: PairPreview?,
    askNotifications: Boolean,
    onAskNotificationsHandled: () -> Unit,
) {
    val haptics = rememberHaptics()
    val resources = LocalResources.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val requestNotificationsAdded = rememberNotificationPermissionRequest(NotificationAsk.PAIRS_ADDED)
    LaunchedEffect(askNotifications) {
        if (!askNotifications) return@LaunchedEffect
        onAskNotificationsHandled()
        requestNotificationsAdded()
    }
    CollectScreenEvents(holder.events) { event ->
        if (holder.handleShared(event, viewModel, banner, haptics, resources)) return@CollectScreenEvents
        when (event) {
            WatchUiEvent.RequestNotifications -> requestNotifications()
            WatchUiEvent.PreviewFull -> {
                // Merkliste voll: nichts angelegt, sagen warum (Zahl im Format der App-Sprache)
                addState.pending = false
                haptics.play(HapticEvent.WARNING)
                banner.show(
                    resources.getString(R.string.watchlist_limit_reached, LocaleNumbers.integer(WatchlistLimits.MAX_PAIRS, grouping = true)),
                    SnackbarKind.ERROR,
                )
            }
            is WatchUiEvent.PreviewAdded -> {
                addState.pending = false
                // «Abbrechen», gesperrt bzw. nichts gefunden: Knopf gleich wieder bereit
                val id = event.id ?: return@CollectScreenEvents
                if (event.added) {
                    haptics.play(HapticEvent.SUCCESS)
                    addState.addedId = id
                } else {
                    // Stand schon in der Merkliste: gleich dessen Seite (ohne Halten)
                    preview?.openStored(id, false)
                }
            }
            // Deaktivieren schliesst kein Blatt mehr: die Seite bleibt und zeigt den Hinweis
            else -> Unit
        }
    }
}
