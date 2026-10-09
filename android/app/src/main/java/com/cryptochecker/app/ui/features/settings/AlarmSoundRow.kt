@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.ui.theme.Spacing

/** Anzeigename eines «Alarm-Signals». */
@androidx.annotation.StringRes
internal fun alarmSignalLabel(signal: AlarmSignal): Int = when (signal) {
    AlarmSignal.SYSTEM -> R.string.alarm_signal_system
    AlarmSignal.SOUND_VIBRATE -> R.string.alarm_signal_sound_vibrate
    AlarmSignal.SOUND -> R.string.alarm_signal_sound
    AlarmSignal.VIBRATE -> R.string.alarm_signal_vibrate
    AlarmSignal.SILENT -> R.string.alarm_signal_silent
}

/**
 * «Alarm-Signal» mit Kurzwert («Nur Vibration»); ein Tipp öffnet die Auswahl.
 * Darunter der Weg in die Systemeinstellungen des Kanals, der gerade gilt —
 * bei «System» stellt man Ton und Vibration genau dort ein.
 */
@Composable
internal fun AlarmSignalRow(
    selected: AlarmSignal,
    onSelected: (AlarmSignal) -> Unit,
    channelId: () -> String,
) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    SubPageRow(
        title = stringResource(R.string.settings_alarm_signal),
        subtitle = if (selected == AlarmSignal.SYSTEM) stringResource(R.string.settings_alarm_signal_system_hint) else null,
        value = stringResource(alarmSignalLabel(selected)),
        onClick = { open = true }
    )
    TextButton(
        onClick = {
            val intent = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Text(stringResource(R.string.settings_alarm_signal_open_system))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_alarm_signal)) },
            text = {
                Column {
                    AlarmSignal.entries.forEach { signal ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = signal == selected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton
                                ) {
                                    open = false
                                    onSelected(signal)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = signal == selected, onClick = null)
                            Text(
                                stringResource(alarmSignalLabel(signal)),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * Alarmton (#202): Systemton aus der Android-Auswahl, eigene Audiodatei
 * (ab Android 10) oder zurück zum Standardton.
 */
@Composable
internal fun AlarmSoundRow(
    soundName: String?,
    isCustom: Boolean,
    currentUri: String?,
    onPicked: (android.net.Uri?) -> Unit,
    onFile: (android.net.Uri) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri: android.net.Uri? = androidx.core.content.IntentCompat.getParcelableExtra(
            result.data ?: return@rememberLauncherForActivityResult,
            android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            android.net.Uri::class.java
        )
        // «Standard» in der Auswahl = Standard-Mitteilungston
        val isDefault = uri == null || android.media.RingtoneManager.isDefault(uri)
        onPicked(if (isDefault) null else uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFile)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menu = true }
                .padding(vertical = Spacing.lg)
        ) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_alarm_sound), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isCustom) soundName ?: stringResource(R.string.settings_alarm_sound_custom)
                    else stringResource(R.string.settings_alarm_sound_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_alarm_sound_system)) },
                onClick = {
                    menu = false
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                            android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM
                        )
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            currentUri?.let { android.net.Uri.parse(it) }
                                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                        )
                    }
                    runCatching { pickRingtone.launch(intent) }
                }
            )
            if (com.cryptochecker.app.notification.AlarmSoundStore.supportsCustomFile) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alarm_sound_file)) },
                    onClick = {
                        menu = false
                        runCatching { pickFile.launch(arrayOf("audio/*")) }
                    }
                )
            }
            if (isCustom) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alarm_sound_reset)) },
                    onClick = { menu = false; onPicked(null) }
                )
            }
        }
    }
}
