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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.BulkAddRunner
import com.cryptochecker.app.ui.components.BulkAddProgress
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
 * schon gemeldet hat — mit «Rückgängig» nur kurz nach dem Ende ([BulkAddRunner.undoOffered]). Mit Fehler beendet: die
 * allgemeine Fehler-Meldung; abgebrochen: nichts.
 */
@Composable
internal fun WatchlistBulkAdd(
    banner: WatchlistBanner,
    modifier: Modifier = Modifier,
    viewModel: WatchlistBulkAddViewModel = hiltViewModel(),
) {
    val progress by viewModel.runner.progress.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    val failedText = stringResource(R.string.something_went_wrong)
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
            // Neue Paare angelegt und eben erst fertig: «Rückgängig» entfernt diese wieder (ausser solchen mit Alarm)
            val ids = result.addedIds
            val offered = ids.isNotEmpty() && viewModel.runner.undoOffered(current)
            val undo = if (offered) ({ viewModel.runner.undo(ids) }) else null
            banner.show(text, bulkAddedKind(summary), onUndo = undo)
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
