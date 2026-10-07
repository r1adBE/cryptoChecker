package com.cryptochecker.app.ui.features.portfolio

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CutoffCsvTexts
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.ui.theme.tabularNumbers
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Stichtag-Export: Datum wählen (Standard 31.12. des Vorjahrs), Währung aus der
 * Einstellung «Umrechnen in» (nur Anzeige), dann CSV-Datei anlegen lassen.
 * Der Dialog bleibt während des Exports offen und zeigt den Fortschritt.
 */
@Composable
fun PortfolioExportDialog(
    currency: String,
    onDismiss: () -> Unit,
    viewModel: PortfolioExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = state == CutoffExportState.Running

    // Als Epochentag, damit die Wahl einen Neuaufbau (und die Dateiauswahl) übersteht
    var epochDay by rememberSaveable { mutableLongStateOf(CutoffExport.defaultDate(LocalDate.now()).toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    var pickDate by remember { mutableStateOf(false) }

    val texts = CutoffCsvTexts(
        coin = stringResource(R.string.portfolio_export_col_coin),
        amount = stringResource(R.string.portfolio_export_col_amount),
        priceUsdt = stringResource(R.string.portfolio_export_col_price),
        valueUsdt = stringResource(R.string.portfolio_export_col_value),
        rate = stringResource(R.string.portfolio_export_col_rate, currency),
        valueTarget = stringResource(R.string.portfolio_export_col_value_cur, currency),
        note = stringResource(R.string.portfolio_export_col_note),
        total = stringResource(R.string.portfolio_export_total),
        noPrice = stringResource(R.string.portfolio_export_no_price),
        noFx = stringResource(R.string.portfolio_export_no_fx),
        incomplete = stringResource(R.string.portfolio_export_incomplete),
        // Ohne Argumente bleibt %1$s … stehen und wird erst beim Bauen ersetzt
        commentFormat = stringResource(R.string.portfolio_export_comment),
    )

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.export(it, date, currency, texts) } }

    val doneText = stringResource(R.string.portfolio_export_done)
    val failedText = stringResource(R.string.portfolio_export_failed)
    LaunchedEffect(state) {
        val current = state
        val text = when (current) {
            CutoffExportState.Done -> doneText
            CutoffExportState.Failed -> failedText
            else -> return@LaunchedEffect
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consume()
        if (current == CutoffExportState.Done) onDismiss()
    }

    if (pickDate) {
        DateDialog(
            selected = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onSelect = { epochDay = it.toEpochDay(); pickDate = false },
            onDismiss = { pickDate = false }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.portfolio_export_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.portfolio_export_date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !running) { pickDate = true }
                        .padding(vertical = 8.dp)
                )
                // Stichtag heute (UTC): Tag noch nicht abgeschlossen, es gilt der aktuelle Kurs
                if (!date.isBefore(LocalDate.now(java.time.ZoneOffset.UTC))) {
                    Text(
                        stringResource(R.string.portfolio_export_today_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    stringResource(R.string.portfolio_export_currency, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    stringResource(R.string.portfolio_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.portfolio_export_running),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = { launcher.launch(CutoffExport.fileName(date)) }
            ) { Text(stringResource(R.string.portfolio_export_button)) }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
