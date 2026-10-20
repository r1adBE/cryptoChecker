// Bestätigungen des Alarm-Bildschirms als Snackbar (Teil von `AlarmsScreen`): mit Aktion und einer Dauer, die mit
// Screenreader länger sein kann. Eigene Datei, weil AlarmsScreen sonst zu lang würde.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.AccessibilityManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Snackbar [text] mit Aktion [actionLabel], [millis] lang — mit Screenreader ggf. länger (Systemeinstellung «Zeit
 * für Aktionen»). Eine stehende Snackbar macht Platz. null = abgelaufen, ohne dass die Aktion getippt wurde.
 */
internal suspend fun SnackbarHostState.showTimed(
    text: String,
    actionLabel: String,
    millis: Long,
    accessibilityManager: AccessibilityManager?,
): SnackbarResult? {
    currentSnackbarData?.dismiss()
    val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
        millis, containsIcons = false, containsText = true, containsControls = true
    ) ?: millis
    return withTimeoutOrNull(timeout) {
        showSnackbar(text, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
    }
}

/**
 * Warten, bis weder die Bestätigung des allerersten Alarms noch der Hinweis «Wann Alarme geprüft werden» offen ist:
 * Eine Snackbar mit «Rückgängig» liefe sonst unerreichbar hinter dem Dialog ab.
 */
internal suspend fun awaitAlarmDialogsClosed(firstAlarm: StateFlow<Boolean>, timingHint: StateFlow<Boolean>) {
    combine(firstAlarm, timingHint) { first, hint -> first || hint }.first { open -> !open }
}
