package com.cryptochecker.app.ui.features.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einmaliger Hinweis über der ganzen App: Gespeicherte Daten liessen sich nicht entsperren (Schlüssel verloren), die
 * App hat leer begonnen, nichts wurde gelöscht — Weg zurück über «Sichern & Wiederherstellen» ([StorageRecoveryNotice]).
 * Wie iOS `storageRecoveryNotice()`.
 */
@Composable
internal fun StorageRecoveryNoticeDialog() {
    val context = LocalContext.current
    // Saveable: die Vormerkung ist nach take() gelöscht, der Dialog muss Drehen überstehen
    var visible by rememberSaveable { mutableStateOf(false) }
    val changes by StorageRecoveryNotice.changes.collectAsState()
    LaunchedEffect(changes) {
        if (withContext(Dispatchers.IO) { StorageRecoveryNotice.take(context.applicationContext) }) visible = true
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = { visible = false },
            title = { Text(stringResource(R.string.storage_recovery_title)) },
            text = { Text(stringResource(R.string.storage_recovery_message, stringResource(R.string.backup_title))) },
            confirmButton = {
                TextButton(onClick = { visible = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}
