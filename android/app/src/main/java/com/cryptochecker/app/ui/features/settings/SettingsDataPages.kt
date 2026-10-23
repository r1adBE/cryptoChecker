package com.cryptochecker.app.ui.features.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import com.cryptochecker.app.ui.components.DialogText
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
import androidx.compose.runtime.collectAsState
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.data.BackupRestorePolicy
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.util.LocaleNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Kurs-Mitteilungen der eingespielten Paare nie an — eine Neuinstallation hat sie noch nie gefragt.
    // Wie neue Paare: erst kurz erklären, dann die System-Abfrage
    val requestNotifications = com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest(
        com.cryptochecker.app.ui.components.NotificationAsk.PAIRS_ADDED
    )
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restorePicked) }

    // Datenbank unverschlüsselt (Rückfall, DatabaseEncryptionNotice): bei jeder Änderung neu prüfen, nicht im Hauptthread
    val encryptionChanges by DatabaseEncryptionNotice.changes.collectAsState()
    var encryptionPending by remember { mutableStateOf(false) }
    LaunchedEffect(encryptionChanges) {
        encryptionPending = withContext(Dispatchers.IO) { DatabaseEncryptionNotice.isPending(context.applicationContext) }
    }

    SettingsSubPage(title = stringResource(R.string.backup_title), onBack = onBack) {
        GroupCard {
            Hint(stringResource(R.string.backup_hint), top = Spacing.sm)
            // Datenbank-Schlüssel liegt im Android Keystore dieses Geräts: ohne Sicherungsdatei kein Umzug
            Hint(stringResource(R.string.backup_device_bound))
            if (encryptionPending) Hint(stringResource(R.string.backup_encryption_pending))
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
            onDismiss = viewModel::cancelRestore,
            failedAttempts = step.failedAttempts,
        )
        is RestoreStep.Confirm -> AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_import)) },
            text = {
                DialogText {
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
                    // Paare und Alarme über den Grenzen nennen, bevor etwas ersetzt wird (nicht still weglassen)
                    overLimitsText(resources, R.string.backup_restore_over_limits, step.preview.droppedPairs, step.preview.droppedAlarms)
                        ?.let { Text(it, color = AppColors.warningText, modifier = Modifier.padding(top = 8.dp)) }
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

    // Ergebnis mit Hinweisen oder langem Text als Dialog (BackupResultPresentation); Mitteilungs-Frage erst danach
    var resultDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var askAfterResult by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    // Grössere Schrift: Toast fasst weniger (BackupResultPresentation.toastLimit)
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(backupMessage) {
        val m = backupMessage ?: return@LaunchedEffect
        val res = BackupResultPresentation.messageRes(m)
        val text = if (m is BackupMessage.Restored) restoredText(resources, m) else resources.getString(res)
        val asDialog = if (m is BackupMessage.Restored) {
            BackupResultPresentation.asDialog(
                resources.getResourceEntryName(res), text, m.droppedPairs, m.droppedAlarms, m.appLockKept, m.addressServerNote,
                fontScale,
            )
        } else {
            BackupResultPresentation.asDialog(resources.getResourceEntryName(res), text, fontScale = fontScale)
        }
        val askNotifications = m is BackupMessage.Restored && m.watches > 0
        haptics.play(BackupMessage.haptic(m))
        if (asDialog) {
            resultDialog = text
            askAfterResult = askNotifications
        } else {
            if (askNotifications) requestNotifications()
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
        viewModel.clearBackupMessage()
    }
    resultDialog?.let { text ->
        val close = {
            resultDialog = null
            if (askAfterResult) {
                askAfterResult = false
                requestNotifications()
            }
        }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.backup_title)) },
            text = { DialogText { Text(text) } },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/**
 * «Wiederhergestellt: …» samt Hinweisen: über den Grenzen weggelassen; Sperre blieb an (Sicherung ohne Passwort wollte
 * sie ausschalten); eigener Bitcoin-Server nicht übernommen, entfernt oder ersetzt.
 */
private fun restoredText(resources: android.content.res.Resources, m: BackupMessage.Restored): String {
    val restored = resources.getString(
        R.string.backup_restored,
        resources.getQuantityString(R.plurals.backup_restored_pairs, m.watches, m.watches),
        resources.getQuantityString(R.plurals.backup_restored_alarms, m.alarms, m.alarms),
    )
    val serverNote = when (m.addressServerNote) {
        BackupRestorePolicy.AddressServerNote.KEPT -> R.string.backup_restore_server_kept
        BackupRestorePolicy.AddressServerNote.PUBLIC -> R.string.backup_restore_server_public
        BackupRestorePolicy.AddressServerNote.CHANGED -> R.string.backup_restore_server_changed
        null -> null
    }
    return listOfNotNull(
        restored,
        overLimitsText(resources, R.string.backup_restored_over_limits, m.droppedPairs, m.droppedAlarms),
        resources.getString(R.string.backup_restore_app_lock_kept).takeIf { m.appLockKept },
        serverNote?.let { resources.getString(it) },
    ).joinToString("\n")
}

/**
 * «3 Paare und 12 Alarme überschreiten die Grenzen …» mit [format] vor ([R.string.backup_restore_over_limits]) bzw. nach
 * dem Wiederherstellen ([R.string.backup_restored_over_limits]); null, wenn nichts wegfällt.
 */
internal fun overLimitsText(
    resources: android.content.res.Resources,
    @androidx.annotation.StringRes format: Int,
    pairs: Int,
    alarms: Int,
): String? {
    if (pairs <= 0 && alarms <= 0) return null
    return resources.getString(
        format,
        resources.getQuantityString(R.plurals.backup_restored_pairs, pairs, pairs),
        resources.getQuantityString(R.plurals.backup_restored_alarms, alarms, alarms),
        LocaleNumbers.integer(WatchlistLimits.MAX_PAIRS, grouping = true),
        LocaleNumbers.integer(WatchlistLimits.MAX_ALARMS_PER_PAIR),
    )
}
