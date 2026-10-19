package com.cryptochecker.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cryptochecker.app.domain.alarm.AlarmSnoozeStore
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Knopf «1 h später» der Alarm-Meldung (nur Prozent- und Bewegungsalarme, siehe AlarmSnooze.offeredFor): vermerkt
 * die Pause ([AlarmSnoozeStore]) und entfernt die Meldung. Nur die nächste Meldung wartet höchstens eine Stunde
 * (Melde-Schritt, wie die Nachtruhe).
 * Nicht exportiert: nur der eigene PendingIntent erreicht ihn.
 */
@AndroidEntryPoint
class AlarmActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: AppNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNOOZE) return
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, 0L).takeIf { it > 0 } ?: return
        AlarmSnoozeStore(context).snooze(alarmId, System.currentTimeMillis())
        // Erledigt: Meldung weg (samt leerer Zusammenfassung der Gruppe)
        runCatching { notifier.cancelAlarms(listOf(alarmId)) }
            .onFailure { Timber.w(it, "Alarm-Meldung nach «1 h später» nicht entfernt") }
    }

    companion object {
        const val ACTION_SNOOZE = "com.cryptochecker.app.action.SNOOZE_ALARM"
        const val EXTRA_ALARM_ID = "com.cryptochecker.app.extra.ALARM_ID"
    }
}
