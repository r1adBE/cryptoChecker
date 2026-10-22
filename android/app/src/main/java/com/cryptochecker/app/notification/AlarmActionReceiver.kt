package com.cryptochecker.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.alarm.AlarmSnoozeStore
import com.cryptochecker.app.util.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Knopf «1 h später» der Alarm-Meldung (wo die Pause wirkt, siehe AlarmSnooze.offeredFor): vermerkt die Pause
 * ([AlarmSnoozeStore]) und entfernt die Meldung. Nur die nächste Meldung wartet höchstens eine Stunde (Melde-Schritt,
 * wie die Nachtruhe). Kursmarken ([EXTRA_REARM], AlarmSnooze.rearmsOnSnooze) werden zusätzlich wieder scharf gestellt
 * und — falls einmalig und darum nach dem Melden aus — wieder eingeschaltet: Liegt der Kurs nach der Pause noch
 * jenseits der Marke, meldet der Alarm erneut, sonst beim nächsten Überqueren.
 * Nicht exportiert: nur der eigene PendingIntent erreicht ihn.
 */
@AndroidEntryPoint
class AlarmActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: AppNotifier

    @Inject lateinit var watchRepository: WatchRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNOOZE) return
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, 0L).takeIf { it > 0 } ?: return
        // Zuerst die Pause: Stellt das Einschalten den Alarm scharf, hält sie die sofortige Meldung zurück
        AlarmSnoozeStore(context).snooze(alarmId, System.currentTimeMillis())
        // Erledigt: Meldung weg (samt leerer Zusammenfassung der Gruppe)
        runCatching { notifier.cancelAlarms(listOf(alarmId)) }
            .onFailure { Timber.w(it, "Alarm-Meldung nach «1 h später» nicht entfernt") }
        if (intent.getBooleanExtra(EXTRA_REARM, false)) {
            // Einschalten stellt Kursmarken zugleich scharf (WatchRepository.setAlarmEnabled → rearmAlarm)
            launchAsync("Kursmarke nach «1 h später» scharf stellen") { watchRepository.setAlarmEnabled(alarmId, true, fromUser = false) }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.cryptochecker.app.action.SNOOZE_ALARM"
        const val EXTRA_ALARM_ID = "com.cryptochecker.app.extra.ALARM_ID"

        /** Kursmarke: nach der Pause wieder scharf (AlarmSnooze.rearmsOnSnooze). */
        const val EXTRA_REARM = "com.cryptochecker.app.extra.REARM"
    }
}
