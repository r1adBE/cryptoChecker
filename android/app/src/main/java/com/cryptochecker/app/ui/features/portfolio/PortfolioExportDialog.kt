package com.cryptochecker.app.ui.features.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Stichtag-Export: Datum wählen (Standard 31.12. des Vorjahrs), Währung aus der
 * Einstellung «Umrechnen in» (nur Anzeige), dann CSV-Datei anlegen lassen.
 * Der Dialog bleibt während des Exports offen und zeigt den Fortschritt; «Abbrechen» (bzw. Zurück) bricht den Export ab
 * und löscht die angefangene Datei. Fehler nennt der Dialog nach ihrer Art, mit «Erneut versuchen».
 * Die Steuer-Arbeitsunterlage hat eine eigene Seite ([TaxWorksheetScreen]).
 */
@Composable
fun PortfolioExportDialog(
    currency: String,
    onDismiss: () -> Unit,
    /** Export fertig: Bestätigung über die Snackbar des Aufrufers (der Dialog ist dann schon zu). */
    onDone: (String) -> Unit = {},
    viewModel: PortfolioExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = state == CutoffExportState.Running

    // Als Epochentag, damit die Wahl einen Neuaufbau (und die Dateiauswahl) übersteht
    var epochDay by rememberSaveable { mutableLongStateOf(CutoffExport.defaultDate(LocalDate.now()).toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    var pickDate by remember { mutableStateOf(false) }
    val texts = cutoffTexts(currency)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.export(it, date, currency, texts) } }
    // «Erneut versuchen» nach «Datei nicht schreibbar»: neue Datei wählen lassen, sonst dieselbe
    val retryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { viewModel.retryExport(it) } }

    // Schliessen ohne Lauf: eine stehende Fehlermeldung gilt nicht fürs nächste Öffnen
    val close = { viewModel.dismissFailure(); onDismiss() }
    val doneText = stringResource(R.string.portfolio_export_done)
    val haptics = rememberHaptics()
    LaunchedEffect(state) {
        when (state) {
            CutoffExportState.Done -> {
                viewModel.consume()
                haptics.play(HapticEvent.SUCCESS)
                // Dialog zu, Bestätigung in der Snackbar des Portfolio-Tabs
                onDone(doneText)
                onDismiss()
            }
            // Fehlgeschlagen: Dialog bleibt offen, Meldung mit «Erneut versuchen» darin
            is CutoffExportState.Failed -> haptics.play(HapticEvent.ERROR)
            else -> Unit
        }
    }

    if (pickDate) {
        DateDialog(
            selected = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onSelect = { epochDay = it.toEpochDay(); pickDate = false },
            onDismiss = { pickDate = false }
        )
    }

    AlertDialog(
        // Zurück während des Exports bricht ihn ab (Dialog bleibt offen)
        onDismissRequest = { if (running) viewModel.cancelExport() else close() },
        title = { Text(stringResource(R.string.portfolio_export_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.portfolio_export_date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    date.format(LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                    style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (running) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
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
                if (running) ExportRunningRow(modifier = Modifier.padding(top = 16.dp))
                (state as? CutoffExportState.Failed)?.let { failed ->
                    ExportFailureRow(
                        kind = failed.kind,
                        onRetry = {
                            if (failed.kind == ExportFailureKind.FILE) {
                                retryLauncher.launch(CutoffExport.fileName(date))
                            } else {
                                viewModel.retryExport()
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = { viewModel.dismissFailure(); launcher.launch(CutoffExport.fileName(date)) }
            ) { Text(stringResource(R.string.portfolio_export_button)) }
        },
        dismissButton = {
            // Während des Exports: abbrechen (angefangene Datei wird gelöscht), sonst schliessen
            TextButton(
                onClick = { if (running) viewModel.cancelExport() else close() },
                modifier = Modifier.testTag(UiTestTags.EXPORT_CANCEL),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
