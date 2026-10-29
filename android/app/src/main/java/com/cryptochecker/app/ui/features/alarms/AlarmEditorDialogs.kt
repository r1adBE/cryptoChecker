// Hinweise des Alarm-Editors: «Bei geschlossener App», der Satz «Wann Alarme geprüft werden» mit «Zuletzt geprüft» und
// sein Dialog.
// Eigene Datei, weil `AlarmEditorSheet` sonst zu lang würde.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Kleine graue Zeile unter dem Alarm-Satz: der eine Satz «Wann Alarme geprüft werden»
 * ([alarmTimingHintText]) — vollständig sichtbar, darum ohne (i), das ihn nur wiederholen würde.
 * Auch im Portfolio-Alarm. Darunter «Zuletzt geprüft» ([AlarmLastCheckedLine]), der Akku-Hinweis, der Knopf
 * «Aktualisierung ändern» direkt zur Wahl «Wie aktuell?» und der Weg zu «Damit Alarme ankommen». Wie iOS `ClosedAppHintRow`.
 */
@Composable
internal fun ClosedAppHint(modifier: Modifier = Modifier) {
    val openUpdates = LocalOpenUpdateSettings.current
    // Eingeschränkter Akku → Hinweis mit Knopf; nach der Rückkehr aus den Android-Einstellungen neu prüfen
    val context = LocalContext.current
    var batteryRestricted by remember { mutableStateOf(!com.cryptochecker.app.util.BatteryOptimization.isIgnoring(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        batteryRestricted = !com.cryptochecker.app.util.BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        Text(
            alarmTimingHintText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AlarmLastCheckedLine(Modifier.padding(top = 2.dp))
        // Ein Alarm ist da, also wird im Hintergrund geprüft (hasWork)
        com.cryptochecker.app.ui.features.settings.BatteryDelayHint(batteryRestricted = batteryRestricted, hasWork = true)
        if (openUpdates != null) {
            TextButton(
                onClick = openUpdates,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.alarm_change_updates), style = MaterialTheme.typography.labelLarge)
            }
        }
        // Was gerade im Weg stehen könnte (Erlaubnis, Kanal, Akku …): eigene Seite mit dem echten Zustand
        LocalOpenAlarmDelivery.current?.let { openDelivery ->
            TextButton(
                onClick = openDelivery,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.delivery_title), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * «Zuletzt geprüft 14:32»: wann die Alarme zuletzt wirklich geprüft wurden — volle Prüfung in App, Hintergrund oder
 * «Pünktlich prüfen» ([RefreshStats.lastFullCheck]); Live-Takte zählen nicht (keine Prüfung aller Alarme). Noch nie:
 * keine Zeile. Liest den Stand jede Minute neu (heute nur die Uhrzeit, sonst mit Datum). Wie iOS `AlarmLastCheckedLine`.
 */
@Composable
internal fun AlarmLastCheckedLine(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var at by remember { mutableLongStateOf(RefreshStats.storedLastFullCheck(context)) }
    // Minutentakt: neuer Stand und der Wechsel «heute» → mit Datum um Mitternacht
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / MINUTE_MILLIS) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
            at = RefreshStats.storedLastFullCheck(context)
            minute = System.currentTimeMillis() / MINUTE_MILLIS
        }
    }
    if (at <= 0) return
    // Neu formatieren, sobald eine Minute vergangen ist (heute ↔ mit Datum)
    val text = androidx.compose.runtime.key(minute) { checkTime(at) }
    Text(
        stringResource(R.string.alarm_last_checked, text),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val MINUTE_MILLIS = 60_000L

/**
 * Der eine Satz «Wann Alarme geprüft werden» ([com.cryptochecker.app.domain.alarm.AlarmTimingHint]):
 * bei geschlossener App unregelmässig, mit «Pünktlich prüfen» etwa alle 15 Minuten. Den Namen des
 * Schalters setzt die App ein, damit er in jeder Sprache genau wie in den Einstellungen heisst.
 */
@Composable
internal fun alarmTimingHintText(): String =
    stringResource(R.string.alarm_timing_hint_android, stringResource(R.string.settings_exact_checks))

/**
 * «Wann Alarme geprüft werden»: einmal nach dem Speichern eines Alarms (nicht beim allerersten, dessen
 * Bestätigung denselben Satz zeigt). Unter dem Satz der Weg zur Wahl «Wie aktuell?» — aus den Texten
 * der Einstellungen, stimmt also in jeder Sprache.
 */
@Composable
internal fun AlarmTimingDialog(onDismiss: () -> Unit) {
    val path = com.cryptochecker.app.domain.alarm.AlarmTimingHint.settingsPath(
        stringResource(R.string.settings_title),
        // «Kurs-Aktualisierung» steht wieder auf der Hauptseite (Gruppe «Allgemein»)
        stringResource(R.string.settings_row_updates),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_timing_title)) },
        text = {
            DialogText(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(alarmTimingHintText())
                Text(stringResource(R.string.alarm_timing_where, path))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
