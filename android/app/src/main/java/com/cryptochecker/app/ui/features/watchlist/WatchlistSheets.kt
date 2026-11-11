package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.components.GroupNameDialog
import com.cryptochecker.app.ui.components.canonicalGroupName
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics

/**
 * Alles, was sich über die Merkliste legt: Bericht, Rückfragen, Gruppen anlegen und bearbeiten, «Was bewegt sich
 * mit?» (aus der Zeile bzw. einer Alarm-Meldung). Ein Paar selbst öffnet die Paarseite (`PairScreen`). Was offen ist,
 * steht in [ui].
 */
@Composable
internal fun WatchlistSheets(
    ui: WatchlistScreenState,
    /** Stand aus dem ViewModel (Frische-Schwelle, Live-Strom, Chart-Art). */
    state: WatchlistUiState,
    viewModel: WatchlistViewModel,
    watches: List<WatchEntity>,
    groups: List<String>,
    notTradedCount: Int,
    rollingBasis: Boolean,
    activeSignals: Map<Long, List<ActivitySignal>>,
    now: Long,
    banner: WatchlistBanner,
) {
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    val lastReport by viewModel.lastRefreshReport.collectAsStateWithLifecycle()
    val appStartMillis by viewModel.appStartMillis.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()
    // Rückmeldungen nach der Rückfrage «nicht gespeichert» im aktuellen Bildschirm (auch nach dem Neuerstellen)
    val uiEvents = rememberWatchUiEvents(WATCHLIST_UI_EVENTS)
    WatchlistUiEffects(uiEvents, ui, viewModel, banner, haptics, resources, requestNotifications)

    if (ui.showReport) {
        RefreshReportSheet(
            report = lastReport,
            now = now,
            appStartMillis = appStartMillis,
            liveExchanges = state.live.liveExchanges,
            onDismiss = { ui.showReport = false },
        )
    }

    // Nicht mehr gehandelte Paare (ganze Merkliste, nicht nur die Gruppe) samt Alarmen
    // entfernen; danach Banner mit «Rückgängig» (holt alle zurück)
    if (ui.askRemoveNotTraded && notTradedCount > 0) {
        ConfirmDialog(
            title = stringResource(R.string.watchlist_remove_not_traded_title),
            text = pluralStringResource(R.plurals.watchlist_remove_not_traded_confirm, notTradedCount, notTradedCount),
            confirm = stringResource(R.string.watchlist_remove_not_traded_action),
            onConfirm = {
                ui.askRemoveNotTraded = false
                viewModel.deleteNotTradedWithUndo { count ->
                    if (count > 0) uiEvents.send(
                        WatchUiEvent.Haptic(HapticEvent.DESTRUCTIVE),
                        WatchUiEvent.Banner(BannerText(plural = R.plurals.watchlist_removed_not_traded, count = count), NOT_TRADED_UNDO_KEY),
                    )
                }
            },
            onDismiss = { ui.askRemoveNotTraded = false }
        )
    }

    if (ui.askClearAll) {
        ConfirmDialog(
            title = stringResource(R.string.watchlist_clear),
            text = stringResource(R.string.watchlist_clear_confirm),
            confirm = stringResource(R.string.watchlist_clear),
            // Wie «Nicht gehandelte entfernen»: danach Banner mit «Rückgängig» (holt alle samt Alarmen zurück)
            onConfirm = {
                ui.askClearAll = false
                viewModel.deleteAllWithUndo { count ->
                    if (count > 0) uiEvents.send(
                        WatchUiEvent.Haptic(HapticEvent.DESTRUCTIVE),
                        WatchUiEvent.Banner(BannerText(plural = R.plurals.watchlist_removed_count, count = count), CLEAR_ALL_UNDO_KEY),
                    )
                }
            },
            onDismiss = { ui.askClearAll = false }
        )
    }

    if (ui.askNewGroup) {
        GroupNameDialog(
            title = stringResource(R.string.group_add),
            initial = "",
            confirmText = stringResource(R.string.group_next),
            onConfirm = { entered -> ui.newGroupNamed(canonicalGroupName(entered, groups), groups) },
            onDismiss = { ui.askNewGroup = false }
        )
    }

    ui.editGroup?.let { name ->
        GroupEditSheet(
            groupName = name,
            isNew = ui.editGroupIsNew,
            watches = watches,
            groups = groups,
            // Wie iOS: Haptik und Blatt zu erst nach der Rückfrage «nicht gespeichert» («Abbrechen»: Blatt bleibt offen)
            onDone = { newName, ids, cancelled ->
                viewModel.saveGroup(if (ui.editGroupIsNew) null else name, newName, ids, onDone = {
                    uiEvents.send(WatchUiEvent.Haptic(HapticEvent.SUCCESS), WatchUiEvent.CloseGroupEdit)
                }, cancelled = cancelled)
            },
            onDelete = { cancelled ->
                viewModel.deleteGroup(name, onDone = {
                    uiEvents.send(WatchUiEvent.Haptic(HapticEvent.DESTRUCTIVE), WatchUiEvent.CloseGroupEdit)
                }, cancelled = cancelled)
            },
            onDismiss = { ui.editGroup = null }
        )
    }

    // Nicht mehr gehandelt bzw. deaktiviert: kein «Was bewegt sich mit?» (auch nicht aus einem gemerkten Zustand)
    ui.whyFor?.let { id -> watches.firstOrNull { it.id == id && !it.isNotTraded && !it.disabled } }?.let { watch ->
        WhySheet(
            watch = watch,
            signals = activeSignals[watch.id].orEmpty(),
            load = viewModel::explain,
            onDismiss = { ui.whyFor = null },
            // «Was bewegt sich mit?» zeigt bei rollender Basis die Änderung selbst; bei «seit 00:00» rechnet es eigene Kerzen
            candleSource = viewModel.foreignCandleSource(watch).takeIf { rollingBasis },
        )
    }
}

/** Rückfrage mit roter Bestätigung (Löschen). */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { DialogText { Text(text) } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
