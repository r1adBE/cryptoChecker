// Dialog «Importe»: frühere CSV-Importläufe (Format, Datum/Zeit, Anzahl), je Lauf «Rückgängig» mit Rückfrage.
// Eigene Datei wie `PortfolioImports.swift`.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatchInfo
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportFormat
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.theme.tabularNumbers

/**
 * ⋯ › «Importe»: CSV-Importläufe (Format, Datum/Zeit, Anzahl), je Lauf «Rückgängig» mit Rückfrage. [onUndo] löscht den
 * Lauf (der Aufrufer zeigt das Banner mit «Rückgängig»).
 */
@Composable
fun PortfolioImportsDialog(
    batches: List<PortfolioImportBatchInfo>,
    onUndo: (PortfolioImportBatchInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<PortfolioImportBatchInfo?>(null) }
    confirm?.let { batch ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.portfolio_import_undo_title)) },
            text = {
                DialogText {
                    Text(pluralStringResource(R.plurals.portfolio_import_undo_confirm, batch.count, batch.count))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirm = null; onUndo(batch); onDismiss() },
                    modifier = Modifier.testTag(UiTestTags.IMPORT_UNDO_CONFIRM),
                ) {
                    Text(stringResource(R.string.portfolio_import_undo), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_imports_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (batches.isEmpty()) {
                    Text(stringResource(R.string.portfolio_imports_empty), style = MaterialTheme.typography.bodyMedium)
                }
                batches.forEach { batch ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            val format = CsvImportFormat.entries.firstOrNull { it.name == batch.format }?.displayName() ?: batch.format ?: "CSV"
                            val time = batch.time?.let {
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))
                            }
                            Text(listOfNotNull(format, time).joinToString(" · "), style = MaterialTheme.typography.bodyMedium.tabularNumbers())
                            Text(
                                pluralStringResource(R.plurals.portfolio_imports_count, batch.count, batch.count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { confirm = batch }, modifier = Modifier.testTag(UiTestTags.IMPORT_UNDO)) {
                            Text(stringResource(R.string.portfolio_import_undo), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
