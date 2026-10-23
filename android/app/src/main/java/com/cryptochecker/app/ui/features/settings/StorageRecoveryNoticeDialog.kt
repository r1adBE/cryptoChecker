package com.cryptochecker.app.ui.features.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.local.StorageRecoveryNotice.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hinweise zum Zustand der gespeicherten Daten über der ganzen App ([StorageRecoveryNotice]); wie iOS
 * `storageRecoveryNotice()`:
 *  - «Gespeicherte Daten liessen sich nicht entsperren»: bleibt, bis «Verstanden» (oder eine Sicherung eingespielt ist);
 *    direkte Aktion «Sicherung wiederherstellen» öffnet Einstellungen › Sicherung ([onRestoreBackup]). Weggetippt erscheint
 *    er beim nächsten Start wieder.
 *  - «Wieder entsperrt», «Wiederherstellen abgebrochen» bzw. «Portfolio nicht aus der Systemsicherung übernommen» (nur
 *    Android): einmal, mit «Schliessen».
 *  - «Einstellungen zurückgesetzt»: erst mit «Schliessen» erledigt — bis dahin auch nach einem Neustart.
 */
@Composable
internal fun StorageRecoveryNoticeDialog(onRestoreBackup: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // Saveable: der Dialog muss Drehen überstehen (einmalige Hinweise sind beim Zeigen schon bestätigt)
    var shown by rememberSaveable { mutableStateOf<Kind?>(null) }
    val changes by StorageRecoveryNotice.changes.collectAsState()
    // Auch nach dem Schliessen (shown → null): ein weiterer vorgemerkter Hinweis folgt
    LaunchedEffect(changes, shown) {
        if (shown != null) return@LaunchedEffect
        val next = withContext(Dispatchers.IO) {
            StorageRecoveryNotice.pending(context)?.also { kind ->
                // Einmalige Hinweise gleich als gesehen merken; «nicht entsperrt» erst mit «Verstanden», «Einstellungen
                // zurückgesetzt» erst beim Schliessen (der Nutzer soll die Sperre sicher prüfen können)
                if (kind != Kind.SET_ASIDE && kind != Kind.SETTINGS_RESET) StorageRecoveryNotice.acknowledge(context, kind)
            }
        }
        shown = next
    }
    fun acknowledge(kind: Kind) {
        // Sofort im Speicher ausblenden (die Prüfung oben läuft gleich wieder), dann dauerhaft bestätigen
        if (kind == Kind.SET_ASIDE) StorageRecoveryNotice.hideSetAsideForNow()
        shown = null
        scope.launch { withContext(Dispatchers.IO) { StorageRecoveryNotice.acknowledge(context, kind) } }
    }
    fun closeSettingsReset() {
        // Erst nach dem Löschen ausblenden: sonst fände die Prüfung oben den Hinweis gleich wieder
        scope.launch {
            withContext(Dispatchers.IO) { StorageRecoveryNotice.acknowledge(context, Kind.SETTINGS_RESET) }
            shown = null
        }
    }
    when (val kind = shown) {
        Kind.SETTINGS_RESET -> AlertDialog(
            onDismissRequest = { closeSettingsReset() },
            title = { Text(stringResource(R.string.settings_reset_notice_title)) },
            text = { Text(stringResource(R.string.settings_reset_notice)) },
            confirmButton = {
                TextButton(onClick = { closeSettingsReset() }) { Text(stringResource(R.string.action_close)) }
            },
        )
        Kind.SET_ASIDE -> AlertDialog(
            onDismissRequest = {
                shown = null
                StorageRecoveryNotice.hideSetAsideForNow()
            },
            title = { Text(stringResource(R.string.storage_recovery_title)) },
            text = { Text(stringResource(R.string.storage_recovery_message, stringResource(R.string.backup_title))) },
            confirmButton = {
                TextButton(onClick = {
                    shown = null
                    // Bis die Sicherung eingespielt ist, bleibt der Hinweis (nächster Start); jetzt nicht über der Auswahl
                    StorageRecoveryNotice.hideSetAsideForNow()
                    onRestoreBackup()
                }) { Text(stringResource(R.string.storage_recovery_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { acknowledge(Kind.SET_ASIDE) }) { Text(stringResource(R.string.storage_recovery_acknowledge)) }
            },
        )
        Kind.KEY_RESTORED, Kind.RESTORE_ROLLED_BACK, Kind.RESTORE_INCOMPLETE, Kind.SYSTEM_BACKUP_NOT_IMPORTED -> AlertDialog(
            onDismissRequest = { shown = null },
            title = {
                Text(
                    stringResource(
                        when (kind) {
                            Kind.KEY_RESTORED -> R.string.storage_recovery_restored_title
                            Kind.SYSTEM_BACKUP_NOT_IMPORTED -> R.string.system_backup_not_imported_title
                            else -> R.string.backup_restore_interrupted_title
                        }
                    )
                )
            },
            text = {
                Text(
                    when (kind) {
                        Kind.KEY_RESTORED -> stringResource(R.string.storage_recovery_restored_message)
                        Kind.RESTORE_ROLLED_BACK -> stringResource(R.string.backup_restore_interrupted_rolled_back)
                        Kind.SYSTEM_BACKUP_NOT_IMPORTED ->
                            stringResource(R.string.system_backup_not_imported_message, stringResource(R.string.backup_title))
                        else -> stringResource(R.string.backup_restore_interrupted_incomplete, stringResource(R.string.backup_title))
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { shown = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
        null -> Unit
    }
}
