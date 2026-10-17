package com.cryptochecker.app.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.notification.AlarmTestResult
import kotlinx.coroutines.launch

/**
 * «Alarm testen»: Android 13+ fragt zuerst nach der Erlaubnis, dann wird
 * [run] ausgeführt. Bei Nachtruhe ein kurzer Hinweis, bei ausgeschalteten
 * Benachrichtigungen ein Dialog mit dem Weg in die App-Einstellungen.
 * @return die Aktion für den Knopf
 */
@Composable
fun rememberAlarmTest(run: suspend () -> AlarmTestResult): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentRun by rememberUpdatedState(run)
    var showDenied by remember { mutableStateOf(false) }

    fun execute() {
        scope.launch {
            when (currentRun()) {
                AlarmTestResult.SENT -> Unit
                AlarmTestResult.SENT_QUIET ->
                    Toast.makeText(context, R.string.alarm_test_quiet, Toast.LENGTH_LONG).show()
                AlarmTestResult.DENIED -> showDenied = true
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) execute() else showDenied = true
    }

    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            text = { Text(stringResource(R.string.alarm_test_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    showDenied = false
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }) { Text(stringResource(R.string.alarm_test_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showDenied = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    return {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        // Noch nicht erlaubt: zuerst fragen (bei endgültiger Ablehnung meldet Android sofort «nein»)
        if (needsPermission) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else execute()
    }
}
