package com.cryptochecker.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Fragt die Benachrichtigungs-Erlaubnis erst, wenn sie gebraucht wird —
 * also wenn eine Meldung oder ein Alarm eingeschaltet wird, nicht beim Start.
 * Ablehnen ist erlaubt; die App arbeitet dann ohne Benachrichtigungen.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { {} }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Erlaubnis für Benachrichtigungen (Android 13+) fehlt noch? Dann fragt
 * [rememberNotificationPermissionRequest] gleich danach — die Bestätigung nach dem Speichern eines
 * Alarms nennt «Mitteilungen aus» dann noch nicht (bei endgültiger Ablehnung zeigt die Alarmliste
 * ohnehin den Hinweis über der Liste).
 */
fun notificationPermissionMissing(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
