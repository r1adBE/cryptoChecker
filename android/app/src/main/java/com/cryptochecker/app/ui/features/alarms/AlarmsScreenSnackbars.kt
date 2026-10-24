// Bestätigungen des Alarm-Bildschirms als Snackbar (Teil von `AlarmsScreen`): mit Aktion und einer Dauer, die mit
// Screenreader länger sein kann. Eigene Datei, weil AlarmsScreen sonst zu lang würde.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind

/**
 * Snackbar [text] mit Aktion [actionLabel], [millis] lang — mit Screenreader ggf. länger (Systemeinstellung «Zeit
 * für Aktionen»). Eine stehende Snackbar macht Platz. null = abgelaufen, ohne dass die Aktion getippt wurde.
 * [kind]: Art der Meldung (Rahmen, Symbol).
 */
internal suspend fun SnackbarHostState.showTimed(
    text: String,
    actionLabel: String,
    millis: Long,
    accessibilityManager: AccessibilityManager?,
    kind: SnackbarKind = SnackbarKind.INFO,
): SnackbarResult? {
    currentSnackbarData?.dismiss()
    val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
        millis, containsIcons = false, containsText = true, containsControls = true
    ) ?: millis
    return withTimeoutOrNull(timeout) {
        showAppSnackbar(text, kind, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
    }
}

/**
 * Hinweis lange stehen lassen (eine stehende Snackbar macht Platz), mit Haptik [event] — etwa «Höchstzahl Alarme»
 * (Warnung) oder «Speicher gesperrt» (Fehler), wie iOS. Beides lehnt etwas ab: Art Fehler.
 */
internal fun SnackbarHostState.showNotice(scope: CoroutineScope, text: String, haptics: HapticPlayer, event: HapticEvent) {
    haptics.play(event)
    scope.launch {
        currentSnackbarData?.dismiss()
        showAppSnackbar(text, SnackbarKind.ERROR, duration = SnackbarDuration.Long)
    }
}

/**
 * Warten, bis weder die Bestätigung des allerersten Alarms noch der Hinweis «Wann Alarme geprüft werden» offen ist:
 * Eine Snackbar mit «Rückgängig» liefe sonst unerreichbar hinter dem Dialog ab.
 */
internal suspend fun awaitAlarmDialogsClosed(firstAlarm: StateFlow<Boolean>, timingHint: StateFlow<Boolean>) {
    combine(firstAlarm, timingHint) { first, hint -> first || hint }.first { open -> !open }
}

/**
 * «Rückgängig» nach einem Schnell-Alarm, das noch gezeigt werden muss: [alarmId] und Text der Snackbar, angelegt um
 * [createdAt]. Liegt im ViewModel ([AlarmUndoOffer]) statt im Bildschirm — öffnet «Prüfen» im Dialog nach dem
 * allerersten Alarm «Damit Alarme ankommen», verlässt der Bildschirm die Komposition; zurück erscheint die Snackbar
 * dann noch, statt unbemerkt abgelaufen zu sein.
 */
internal data class PendingAlarmUndo(val alarmId: Long, val text: String, val createdAt: Long) {
    /** Nicht ewig: Wer erst viel später zurückkommt, löscht den Alarm in der Liste. */
    fun isFresh(now: Long): Boolean = now - createdAt in 0..MAX_AGE_MILLIS

    companion object {
        const val MAX_AGE_MILLIS = 10L * 60_000
    }
}

/** Offenes «Rückgängig» ([PendingAlarmUndo]); nur auf dem Main-Thread geändert (ViewModel, Compose). */
internal class AlarmUndoOffer {
    private val _pending = MutableStateFlow<PendingAlarmUndo?>(null)
    val pending: StateFlow<PendingAlarmUndo?> = _pending.asStateFlow()

    fun offer(undo: PendingAlarmUndo) {
        _pending.value = undo
    }

    /** Gezeigt und erledigt (getippt, abgelaufen oder zu alt); ein inzwischen neueres Angebot bleibt. */
    fun done(alarmId: Long) {
        _pending.update { if (it?.alarmId == alarmId) null else it }
    }
}

/**
 * Zeigt das offene «Rückgängig» ([offer]), sobald kein Dialog mehr offen ist ([awaitAlarmDialogsClosed]) und der
 * Bildschirm wieder vorne steht (RESUMED) — auch nach der Rückkehr aus «Damit Alarme ankommen». Verlässt der
 * Bildschirm die Komposition, bricht die Snackbar ab und beginnt zurück von vorn. [onUndo]: Alarm wieder löschen.
 */
@Composable
internal fun PendingUndoSnackbar(
    offer: AlarmUndoOffer,
    firstAlarm: StateFlow<Boolean>,
    timingHint: StateFlow<Boolean>,
    snackbar: SnackbarHostState,
    millis: Long,
    onUndo: (Long) -> Unit,
) {
    val pending by offer.pending.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val undoLabel = stringResource(R.string.action_undo)
    val accessibilityManager = LocalAccessibilityManager.current
    val haptics = rememberHaptics()
    LaunchedEffect(pending, resumed) {
        val undo = pending ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        // Allererster Alarm: erst nach dem Dialog, sonst liefe «Rückgängig» unerreichbar dahinter ab
        awaitAlarmDialogsClosed(firstAlarm, timingHint)
        if (undo.isFresh(System.currentTimeMillis())) {
            val result = snackbar.showTimed(undo.text, undoLabel, millis, accessibilityManager, SnackbarKind.SUCCESS)
            if (result == SnackbarResult.ActionPerformed) {
                haptics.play(HapticEvent.SUCCESS)
                onUndo(undo.alarmId)
            }
        }
        offer.done(undo.alarmId)
    }
}
