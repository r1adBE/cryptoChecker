package com.cryptochecker.app.ui.features.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalResources
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.UiTestTags

// ── Daten ────────────────────────────────────────────────────────────────────

/** Sichern & Wiederherstellen: eine Datei, die der Nutzer selbst ablegt. */
@Composable
internal fun BackupPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val reason = stringResource(R.string.portfolio_lock_reason)
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val restoreStep by viewModel.restoreStep.collectAsStateWithLifecycle()
    // Dialog «Sichern» offen: Vorwahl «Mit Passwort schützen» (true, wenn Portfolio-Daten dabei sind)
    var exportDialog by remember { mutableStateOf<Boolean?>(null) }
    // Wieder gesperrt (Hintergrund-Limit), während «Sichern» offen ist: schliessen wie die
    // Erfassen-Blätter. Eine schon bestätigte Sicherung (Dateiauswahl offen) läuft weiter.
    val sessionLocked by viewModel.sessionLocked.collectAsStateWithLifecycle()
    LaunchedEffect(sessionLocked) {
        if (sessionLocked) exportDialog = null
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) else viewModel.cancelExport() }
    // Nach dem Einspielen: Erlaubnis für Mitteilungen erfragen (Android 13+), sonst kommen Alarme und
    // Kurs-Mitteilungen der eingespielten Paare nie an — eine Neuinstallation hat sie noch nie gefragt
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restorePicked) }

    SettingsSubPage(title = stringResource(R.string.backup_title), onBack = onBack) {
        GroupCard {
            Hint(stringResource(R.string.backup_hint), top = 10.dp)
            SettingsAnchor("backup.actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)
                ) {
                    FilledTonalButton(
                        onClick = {
                            // Mit Portfolio-Daten und gesperrt: erst entsperren
                            viewModel.startBackupExport(context.findFragmentActivity(), reason) { hasPortfolio ->
                                exportDialog = hasPortfolio
                            }
                        },
                        modifier = Modifier.weight(1f).testTag(UiTestTags.BACKUP_EXPORT)
                    ) { Text(stringResource(R.string.backup_export), maxLines = 1) }
                    FilledTonalButton(
                        onClick = {
                            viewModel.startRestore(context.findFragmentActivity(), reason) {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }
                        },
                        modifier = Modifier.weight(1f).testTag(UiTestTags.BACKUP_IMPORT)
                    ) { Text(stringResource(R.string.backup_import), maxLines = 1) }
                }
            }
        }
    }

    exportDialog?.let { defaultProtect ->
        BackupExportDialog(
            defaultProtect = defaultProtect,
            onConfirm = { password ->
                exportDialog = null
                // Gleicher Weg wie bisher (Dateiauswahl des Systems), nur mit gewähltem Schutz
                viewModel.prepareExport(password)
                exportLauncher.launch("cryptochecker-backup-${java.time.LocalDate.now()}.json")
            },
            onDismiss = { exportDialog = null }
        )
    }

    when (val step = restoreStep) {
        is RestoreStep.Password -> BackupPasswordDialog(
            wrong = step.wrong,
            checking = step.checking,
            onSubmit = { password -> viewModel.submitRestorePassword(step.uri, password) },
            onDismiss = viewModel::cancelRestore
        )
        is RestoreStep.Confirm -> AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_import)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_restore_confirm))
                    // Ohne Passwort keine Echtheitsprüfung: Herkunft prüfen lassen
                    if (step.unencrypted) {
                        Text(
                            stringResource(R.string.backup_restore_unencrypted_warning),
                            color = AppColors.warningText,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    // Ungültige Portfolio-Einträge nennen, statt sie still wegzulassen
                    if (step.invalid > 0) {
                        Text(
                            pluralStringResource(R.plurals.backup_restore_invalid, step.invalid, step.invalid),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restoreBackup(step.uri, step.password, step.invalid) },
                    modifier = Modifier.testTag(UiTestTags.BACKUP_RESTORE_CONFIRM),
                ) {
                    Text(stringResource(R.string.backup_import), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) }
            }
        )
        null -> Unit
    }

    val exportedText = stringResource(R.string.backup_exported)
    val failedText = stringResource(R.string.backup_failed)
    val tooLargeText = stringResource(R.string.backup_too_large)
    val newerVersionText = stringResource(R.string.backup_restore_newer_version)
    val restoredFormat = stringResource(R.string.backup_restored)
    val appLockKeptText = stringResource(R.string.backup_restore_app_lock_kept)
    LaunchedEffect(backupMessage) {
        val text = when (val m = backupMessage) {
            BackupMessage.Exported -> exportedText
            BackupMessage.Failed -> failedText
            BackupMessage.TooLarge -> tooLargeText
            BackupMessage.NewerVersion -> newerVersionText
            is BackupMessage.Restored -> {
                if (m.watches > 0) requestNotifications()
                val restored = restoredFormat.format(
                    resources.getQuantityString(R.plurals.backup_restored_pairs, m.watches, m.watches),
                    resources.getQuantityString(R.plurals.backup_restored_alarms, m.alarms, m.alarms)
                )
                // Sperre blieb an (Sicherung ohne Passwort wollte sie ausschalten)
                if (m.appLockKept) restored + "\n" + appLockKeptText else restored
            }
            null -> return@LaunchedEffect
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearBackupMessage()
    }
}
