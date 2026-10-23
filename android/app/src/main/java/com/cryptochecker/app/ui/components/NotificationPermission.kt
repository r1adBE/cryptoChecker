package com.cryptochecker.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags

/** Anlass der Frage nach der Benachrichtigungs-Erlaubnis ([rememberNotificationPermissionRequest]). */
enum class NotificationAsk {
    /** Ausdrücklich eingeschaltet (Schalter in den Einstellungen, Meldung eines Paars): gleich die System-Abfrage. */
    DIRECT,
    /** Neue Paare (mit Kurs-Mitteilungen) hinzugefügt: vorher einmal kurz erklären ([NotificationPrePrompt]). */
    PAIRS_ADDED,
    /** Alarm angelegt: erklärt, falls noch nie gezeigt — nach «Später» noch genau einmal. */
    ALARM_CREATED,
}

/**
 * Kurze Erklärung vor der System-Abfrage (gespiegelt in `NotificationPrePrompt.swift`): nur, wenn das System
 * wirklich fragen würde (noch nie gefragt) — nie bei schon erlaubt oder abgelehnt. «Weiter» → System-Abfrage;
 * «Später» → nichts, beim ersten angelegten Alarm höchstens noch einmal. Danach nicht mehr.
 */
object NotificationPrePrompt {
    /** Noch nie gezeigt. */
    const val STAGE_NEW = 0
    /** Einmal «Später»: beim ersten Alarm noch einmal. */
    const val STAGE_LATER_ONCE = 1
    /** Zweimal «Später»: nicht mehr erklären, nicht fragen. */
    const val STAGE_DONE = 2
    /** «Weiter» getippt: würde das System trotzdem noch fragen, gleich die System-Abfrage. */
    const val STAGE_CONTINUED = 3

    enum class Action { EXPLAIN, SYSTEM, NOTHING }

    /** Reine Regel — wie `NotificationPrePrompt.action` (iOS). [notDetermined]: das System würde jetzt fragen. */
    fun action(notDetermined: Boolean, stage: Int, reason: NotificationAsk): Action = when {
        !notDetermined || reason == NotificationAsk.DIRECT -> Action.SYSTEM
        stage == STAGE_CONTINUED -> Action.SYSTEM
        stage == STAGE_NEW -> Action.EXPLAIN
        stage == STAGE_LATER_ONCE && reason == NotificationAsk.ALARM_CREATED -> Action.EXPLAIN
        else -> Action.NOTHING
    }

    /** Stufe nach «Später»: beim ersten Mal noch eine Chance (Alarm), danach erledigt. */
    fun afterLater(stage: Int): Int = if (stage == STAGE_NEW) STAGE_LATER_ONCE else STAGE_DONE

    // Eigene SharedPreferences-Datei, bewusst NICHT in backup_rules.xml / data_extraction_rules.xml:
    // die Erlaubnis gehört zu diesem Gerät
    private const val PREFS = "notification_preprompt"
    private const val KEY_STAGE = "stage"
    private const val KEY_SYSTEM_ASKED = "system_asked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stage(context: Context): Int = prefs(context).getInt(KEY_STAGE, STAGE_NEW)

    fun setStage(context: Context, stage: Int) = prefs(context).edit { putInt(KEY_STAGE, stage) }

    /** Die System-Abfrage wurde gestartet (auch aus dem Probe-Alarm, [rememberAlarmTest]). */
    fun markSystemAsked(context: Context) = prefs(context).edit { putBoolean(KEY_SYSTEM_ASKED, true) }

    /**
     * Würde Android jetzt fragen? Nicht erlaubt, nie von hier gefragt und keine Begründung fällig (die gibt es erst
     * nach einer Ablehnung). Unter Android 13 gibt es keine Abfrage.
     */
    fun notDetermined(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (prefs(context).getBoolean(KEY_SYSTEM_ASKED, false)) return false
        val activity = context.findActivity() ?: return true
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * Fragt die Benachrichtigungs-Erlaubnis erst, wenn sie gebraucht wird —
 * also wenn eine Meldung oder ein Alarm eingeschaltet wird, nicht beim Start.
 * Bei [NotificationAsk.PAIRS_ADDED] und [NotificationAsk.ALARM_CREATED] erst die kurze Erklärung
 * ([NotificationPrePrompt]), wenn das System wirklich fragen würde.
 * Ablehnen ist erlaubt; die App arbeitet dann ohne Benachrichtigungen.
 */
@Composable
fun rememberNotificationPermissionRequest(reason: NotificationAsk = NotificationAsk.DIRECT): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { {} }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Saveable: die Erklärung muss Drehen überstehen
    var explaining by rememberSaveable { mutableStateOf(false) }

    fun launchSystem() {
        launchOrSkip(launcher) { NotificationPrePrompt.markSystemAsked(context) }
    }

    if (explaining) {
        val later = {
            explaining = false
            NotificationPrePrompt.setStage(context, NotificationPrePrompt.afterLater(NotificationPrePrompt.stage(context)))
        }
        AlertDialog(
            onDismissRequest = later,
            title = { Text(stringResource(R.string.notification_permission_preprompt_title)) },
            text = { Text(stringResource(R.string.notification_permission_preprompt_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explaining = false
                        NotificationPrePrompt.setStage(context, NotificationPrePrompt.STAGE_CONTINUED)
                        launchSystem()
                    },
                    modifier = Modifier.testTag(UiTestTags.NOTIFICATION_PREPROMPT_CONTINUE),
                ) { Text(stringResource(R.string.notification_permission_preprompt_continue)) }
            },
            dismissButton = {
                TextButton(onClick = later) { Text(stringResource(R.string.notification_permission_preprompt_later)) }
            }
        )
    }

    return remember(launcher, reason) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val action = NotificationPrePrompt.action(
                    NotificationPrePrompt.notDetermined(context), NotificationPrePrompt.stage(context), reason
                )
                when (action) {
                    NotificationPrePrompt.Action.EXPLAIN -> explaining = true
                    NotificationPrePrompt.Action.SYSTEM -> launchSystem()
                    NotificationPrePrompt.Action.NOTHING -> Unit
                }
            }
        }
    }
}

/**
 * System-Abfrage über [launcher] starten. ARC5-R01: nie auf einem abgemeldeten Launcher (Komposition schon
 * weg) — der wirft `IllegalStateException`; dann nicht fragen, nicht abstürzen und [onLaunched] (als gefragt merken) nicht
 * aufrufen. true = gestartet. Eigene Funktion, damit ein Unit-Test sie mit einem Fake-Launcher prüft (TST6-R1).
 */
internal fun launchOrSkip(launcher: ActivityResultLauncher<String>, onLaunched: () -> Unit): Boolean {
    try {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } catch (e: IllegalStateException) {
        timber.log.Timber.w(e, "Erlaubnis-Abfrage ohne aktiven Bildschirm übersprungen")
        return false
    }
    onLaunched()
    return true
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
