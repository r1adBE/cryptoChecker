package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.BulkAddRunner
import com.cryptochecker.app.ui.components.BulkAddProgress
import com.cryptochecker.app.ui.components.BulkUndoConfirmDialog
import com.cryptochecker.app.ui.components.rememberBulkUndoConfirm
import com.cryptochecker.app.ui.features.explorer.BulkAddResult
import com.cryptochecker.app.ui.features.explorer.bulkAddedKind
import com.cryptochecker.app.ui.features.explorer.bulkAddedText
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Zugang der Merkliste zur app-weiten Aufnahme «Mehrere Paare auf einmal» ([BulkAddRunner]). */
@HiltViewModel
internal class WatchlistBulkAddViewModel @Inject constructor(val runner: BulkAddRunner) : ViewModel()

/**
 * Läuft «Mehrere Paare auf einmal» noch (Bildschirm «Paar hinzufügen» verlassen): schmaler Balken oben über der
 * Merkliste. Am Ende das Ergebnis als Banner («746 hinzugefügt, 0 bereits vorhanden.»), falls es der Explorer nicht
 * schon gemeldet hat — mit «Rückgängig» nur kurz nach dem Ende ([BulkAddRunner.undoOffered]); «Rückgängig» fragt erst
 * nach ([BulkUndoConfirmDialog]), nur «Entfernen» ruft [BulkAddRunner.undo]. Mit Fehler beendet: die allgemeine
 * Fehler-Meldung; abgebrochen: nichts.
 */
@Composable
internal fun WatchlistBulkAdd(
    banner: WatchlistBanner,
    modifier: Modifier = Modifier,
    viewModel: WatchlistBulkAddViewModel = hiltViewModel(),
) {
    val progress by viewModel.runner.progress.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val failedText = stringResource(R.string.something_went_wrong)
    // Vor dem frühen Ende: der Dialog bleibt, auch wenn das Ergebnis im Runner schon abgeräumt ist
    val undoConfirm = rememberBulkUndoConfirm()
    val undoExpiredText = stringResource(R.string.bulk_undo_expired)
    // «Entfernen» nach Ablauf des Zeitfensters: nichts löschen, nur sagen, dass die Paare bleiben
    BulkUndoConfirmDialog(
        undoConfirm,
        onExpired = { banner.show(undoExpiredText, SnackbarKind.INFO) },
        onRemove = { viewModel.runner.undo(it) },
    )
    val current = progress ?: return
    val result = current.result
    if (current.running) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 4.dp),
        ) {
            BulkAddProgress(current.done, current.total, Modifier.fillMaxWidth().padding(Spacing.md))
        }
    } else if (result != null) {
        LaunchedEffect(result) {
            val summary = BulkAddResult(added = result.added, skipped = result.skipped, refused = result.refused)
            val text = bulkAddedText(resources, summary)
            // Neue Paare angelegt und eben erst fertig: «Rückgängig» fragt, ob diese wieder weg sollen (ausser solchen
            // mit Alarm)
            val ids = result.addedIds
            val offered = ids.isNotEmpty() && viewModel.runner.undoOffered(current)
            val undo = if (offered) ({ undoConfirm.request(ids, current.finishedAt) }) else null
            // Haptik erst bei «Entfernen» im Dialog; Anzeigedauer wie die Meldung im Explorer
            banner.show(text, bulkAddedKind(summary), undoMillis = BULK_UNDO_MILLIS, undoHaptic = false, onUndo = undo)
            viewModel.runner.clearResult()
        }
    } else {
        // Gesperrt (Hinweis kommt von WatchlistLockedHint), abgebrochen oder Fehler: Ende abräumen
        LaunchedEffect(current) {
            if (current.failed) banner.show(failedText, SnackbarKind.ERROR)
            viewModel.runner.clearResult()
        }
    }
}
