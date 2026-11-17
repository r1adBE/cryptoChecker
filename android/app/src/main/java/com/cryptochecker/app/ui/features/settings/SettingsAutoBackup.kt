package com.cryptochecker.app.ui.features.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.lock.HideOverlaysEffect
import com.cryptochecker.app.ui.theme.DisabledColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/**
 * «Automatisch sichern»: Schalter; beim Einschalten zuerst Ordner wählen, dann Passwort (immer verschlüsselt). Darunter
 * Ordner mit «Ordner ändern», Stand der letzten Sicherung und «Jetzt sichern». Mit Portfolio-Daten und Sperre erst
 * entsperren (wie «Sichern»). Wie iOS `AutoBackupSection`.
 */
@Composable
internal fun AutoBackupSection(settingsViewModel: SettingsViewModel, viewModel: AutoBackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val reason = stringResource(R.string.portfolio_lock_reason)
    // Gewählter Ordner beim Einschalten, bis das Passwort feststeht
    var pickedFolder by remember { mutableStateOf<Uri?>(null) }
    // true = Ordner fürs Einschalten, false = nur Ordner ändern
    var forEnable by remember { mutableStateOf(true) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        if (forEnable) pickedFolder = tree else viewModel.changeFolder(tree)
    }
    fun pickFolder(enable: Boolean) {
        settingsViewModel.startBackupExport(context.findFragmentActivity(), reason) {
            forEnable = enable
            folderLauncher.launch(null)
        }
    }

    GroupCard {
        SettingsAnchor("backup.auto") {
            SwitchRow(
                title = stringResource(R.string.auto_backup_title),
                subtitle = stringResource(R.string.auto_backup_hint),
                checked = state.enabled,
                onCheckedChange = { on -> if (on) pickFolder(enable = true) else viewModel.disable() }
            )
        }
        if (state.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = Spacing.sm)) {
                state.folderName?.let { Hint(it) }
                Text(
                    text = autoBackupStatus(context, state.lastSuccessAt, state.lastFailed),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.lastFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    enabled = !running,
                    colors = DisabledColors.filledTonalButton(),
                    onClick = viewModel::backUpNow,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
                ) { Text(stringResource(R.string.auto_backup_now), maxLines = 1) }
                TextButton(onClick = { pickFolder(enable = false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auto_backup_change_folder))
                }
            }
        }
    }

    // Passwort festlegen (immer mit Passwort: die Dateien liegen in einem fremden Ordner, oft in der Cloud)
    pickedFolder?.let { tree ->
        HideOverlaysEffect(true)
        BackupExportDialog(
            defaultProtect = true,
            hasPortfolioData = true,
            onConfirm = { password ->
                pickedFolder = null
                if (password != null) viewModel.enable(tree, password)
            },
            onDismiss = { pickedFolder = null },
            note = stringResource(R.string.auto_backup_password_hint),
        )
    }
}

/** «Zuletzt gesichert: 17.11.2026, 18:30», «Noch nicht gesichert» oder der Fehlerhinweis. */
private fun autoBackupStatus(context: android.content.Context, lastSuccessAt: Long, failed: Boolean): String = when {
    failed -> context.getString(R.string.auto_backup_failed)
    lastSuccessAt <= 0 -> context.getString(R.string.auto_backup_never)
    else -> context.getString(
        R.string.auto_backup_last,
        android.text.format.DateUtils.formatDateTime(
            context,
            lastSuccessAt,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME
        )
    )
}
