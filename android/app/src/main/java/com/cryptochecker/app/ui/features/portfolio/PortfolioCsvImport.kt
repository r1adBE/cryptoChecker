@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.util.LocaleNumbers
import kotlinx.coroutines.launch

/**
 * Dateiauswahl: CSV kommt je nach Quelle (Drive, Downloads, Mail) als Excel-, Binär- oder beliebiger Text-Typ;
 * der Inhalt wird ohnehin geprüft (sonst «nicht lesbar»).
 */
private val CSV_MIME_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "text/plain", "text/*",
    "application/csv", "application/vnd.ms-excel", "application/octet-stream",
)

/**
 * ⋯ › «CSV importieren»: Dateiauswahl (SAF, Text/CSV), dann Vorschau mit Zählern und den ersten Zeilen,
 * «Importieren» legt die neuen Transaktionen an. Abbruch der Dateiauswahl schliesst den Ablauf.
 */
@Composable
fun PortfolioCsvImportFlow(
    onDismiss: () -> Unit,
    onImported: (Int) -> Unit,
    onPortfolioEnabled: () -> Unit,
    viewModel: PortfolioCsvImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val proposals by viewModel.proposals.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    val linked by viewModel.linked.collectAsStateWithLifecycle()
    // Saveable: die Dateiauswahl ist eine eigene Activity
    var picked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            viewModel.reset()
            onDismiss()
        } else {
            viewModel.load(uri)
        }
    }
    LaunchedEffect(Unit) {
        if (!picked) {
            picked = true
            // Das ViewModel lebt länger als der Ablauf: keine Vorschau eines früheren Imports stehen lassen
            viewModel.reset()
            runCatchingCancellable { launcher.launch(CSV_MIME_TYPES) }
                .onFailure { onDismiss() }
        }
    }
    val close = { viewModel.reset(); onDismiss() }

    when (val s = state) {
        CsvImportState.Idle -> Unit
        // Laden (Datei, Kurse): «Abbrechen» und Zurück brechen ab, gespeichert ist noch nichts. Speichern: kurz, ohne Abbruch
        CsvImportState.Loading, CsvImportState.Saving -> AlertDialog(
            onDismissRequest = { if (s == CsvImportState.Loading) { viewModel.cancelLoad(); onDismiss() } },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.portfolio_csv_import_action)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.portfolio_csv_import_loading))
                }
            },
            confirmButton = {},
            dismissButton = {
                if (s == CsvImportState.Loading) {
                    TextButton(onClick = { viewModel.cancelLoad(); onDismiss() }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
        // Nicht lesbar, zu gross, Portfolio gesperrt (PortfolioStorageLock) oder Schreiben gescheitert: Grund statt «0 importiert»
        CsvImportState.Unreadable, CsvImportState.TooLarge, CsvImportState.Locked, CsvImportState.SaveFailed -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.portfolio_csv_import_action)) },
            text = {
                DialogText {
                    val message = when (s) {
                        // Ganz abgelehnt: eigener Text statt «Änderungen werden nicht gespeichert»
                        CsvImportState.Locked -> R.string.portfolio_csv_import_locked
                        CsvImportState.SaveFailed -> R.string.portfolio_csv_import_failed
                        else -> R.string.portfolio_csv_import_unreadable
                    }
                    // Zu gross: eigener Text mit der Zeilengrenze (16 MB steht im Text),
                    // nicht «kein unterstütztes Format»
                    Text(
                        if (s == CsvImportState.TooLarge) {
                            val maxRows = LocaleNumbers.integer(CsvImportParser.MAX_ROWS, grouping = true)
                            stringResource(R.string.portfolio_csv_import_too_large, maxRows)
                        } else {
                            stringResource(message)
                        }
                    )
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_close)) } },
        )
        is CsvImportState.Preview -> CsvImportPreviewSheet(
            preview = s.preview,
            proposals = proposals,
            choices = choices,
            onChoice = viewModel::setChoice,
            onAllChoices = viewModel::setAllChoices,
            links = links,
            linked = linked,
            onLink = viewModel::setLink,
            onZone = viewModel::setZone,
            onDateOrder = viewModel::setDateOrder,
            onDecimalMark = viewModel::setDecimalMark,
            onDismiss = close,
            // Während des Neu-Lesens kein «Importieren»
            rereading = s.rereading,
            onConfirm = {
                viewModel.confirm(
                    onDone = { count -> onImported(count); onDismiss() },
                    onPortfolioEnabled = onPortfolioEnabled,
                )
            },
        )
    }
}

/** Unterstützte Quellen (Markennamen, nicht übersetzt) — Untertitel im Menü. */
internal const val CSV_IMPORT_SOURCES = "Relai, Swissquote, Kraken, Binance, Coinbase, Bitpanda"
