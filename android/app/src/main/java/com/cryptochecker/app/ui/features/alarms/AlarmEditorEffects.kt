package com.cryptochecker.app.ui.features.alarms

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.components.CollectScreenEvents
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.NotificationAsk
import com.cryptochecker.app.ui.components.notificationPermissionMissing
import com.cryptochecker.app.ui.components.openAppNotificationSettings
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import kotlinx.coroutines.launch
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind

/**
 * Review ARC5-R01: Ergebnisse von Speichern und Schnell-Alarm ([AlarmEditorEvent]) im GERADE gezeigten
 * Bildschirm — auch wenn die Activity während der Rückfrage F-08 neu erstellt wurde: Erlaubnis über den Launcher dieser
 * Komposition, Bestätigung in ihrer Snackbar. Vorher Rückrufe in `AlarmsScreen` (saveDraft, onTemplate), Ablauf unverändert.
 */
@Composable
internal fun AlarmEditorEffects(
    viewModel: AlarmsViewModel,
    snackbar: SnackbarHostState,
    watch: WatchEntity?,
    testAlarm: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accessibilityManager = LocalAccessibilityManager.current
    val haptics = rememberHaptics()
    val settingsLabel = stringResource(R.string.settings_title)
    val activeText = stringResource(R.string.alarm_saved_active)
    val testLabel = stringResource(R.string.alarm_test_action)
    val limitText = alarmLimitText()
    // Alarm angelegt: nach «Später» die Erklärung noch einmal (NotificationPrePrompt)
    val requestNotifications = rememberNotificationPermissionRequest(NotificationAsk.ALARM_CREATED)

    CollectScreenEvents(viewModel.editor.events) { event ->
        val base = watch?.baseAsset.orEmpty()
        val quote = watch?.quoteAsset.orEmpty()
        when (event) {
            // Sicherheitsnetz: Paar inzwischen voll (z. B. Editor offen, Alarm anderswo angelegt) — nichts angelegt, sagen warum
            AlarmEditorEvent.Limit -> snackbar.showNotice(scope, limitText, haptics, HapticEvent.WARNING)
            is AlarmEditorEvent.Created -> {
                requestNotifications()
                haptics.play(HapticEvent.SUCCESS)
                // Den angelegten Alarm nennen («Alarm gespeichert: BTC Steigt um 5 % · einmalig»), nicht nur «erstellt»
                val createdText = alarmSavedText(context, event.alarm, base, quote, notificationsOff = false)
                // Gezeigt von PendingUndoSnackbar: nach dem Dialog bzw. zurück aus «Damit Alarme ankommen»
                viewModel.undo.offer(PendingAlarmUndo(event.alarm.id, createdText, System.currentTimeMillis()))
            }
            is AlarmEditorEvent.Saved -> {
                // Wird die Erlaubnis gleich erst erfragt, nennt die Bestätigung «Mitteilungen aus» noch nicht
                val asking = notificationPermissionMissing(context)
                // Ein Alarm braucht Benachrichtigungen: jetzt fragen (nach dem Speichern), nicht beim Start
                requestNotifications()
                haptics.play(HapticEvent.SUCCESS)
                val off = !event.allowed && !asking
                // Neuer Alarm: eine Zeile «Alarm aktiv · Testen»; beim allerersten Alarm bietet der Bestätigungsdialog
                // das Testen schon an
                if (event.isNew && !off) {
                    if (event.firstEver) return@CollectScreenEvents
                    scope.launch {
                        val result = snackbar.showTimed(
                            activeText, testLabel, ACTIVE_MILLIS, accessibilityManager, SnackbarKind.SUCCESS,
                        )
                        if (result == SnackbarResult.ActionPerformed) testAlarm()
                    }
                    return@CollectScreenEvents
                }
                // «Alarm gespeichert: BTC Kurs über 70’000 USDT · einmalig» (· Mitteilungen aus)
                val text = alarmSavedText(context, event.alarm, base, quote, off)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (off) {
                        // Mit Aktion «Einstellungen» (Mitteilungs-Einstellungen der App)
                        val result = snackbar.showTimed(
                            text, settingsLabel, SAVED_MILLIS, accessibilityManager, SnackbarKind.SUCCESS,
                        )
                        if (result == SnackbarResult.ActionPerformed) openAppNotificationSettings(context)
                    } else {
                        snackbar.showAppSnackbar(text, SnackbarKind.SUCCESS, duration = SnackbarDuration.Short)
                    }
                }
            }
        }
    }
}

/** So lange steht «Alarm gespeichert … · Mitteilungen aus» mit «Einstellungen». */
private const val SAVED_MILLIS = 8_000L

/** So lange steht «Alarm aktiv · Testen» nach einem neuen Alarm. */
private const val ACTIVE_MILLIS = 6_000L
