package com.cryptochecker.app.work

import android.content.Context
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cryptochecker.app.R
import com.cryptochecker.app.data.MacroCalendarRepository
import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.MacroTexts
import com.cryptochecker.app.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Morgen-Meldung «Wirtschaftstermine»: läuft täglich um 08:00 Ortszeit und meldet die
 * wichtigen US-Wirtschaftsdaten des Tages (z. B. «Heute 14:30: US-Inflationsdaten (CPI) – …»).
 * Ohne Termin keine Meldung. Höchstens eine Meldung am Tag; kommt die Aufgabe erst nach
 * 12:00 dran (Doze), entfällt sie für diesen Tag. Nachtruhe gilt wie bei den übrigen
 * Markt-Meldungen (lautlos). Danach plant sie sich für den nächsten Morgen neu.
 * Kalender: [MacroCalendarRepository] (Zwischenspeicher bzw. mitgelieferte Datei, wenn offline).
 */
@HiltWorker
class MacroNotifyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val calendar: MacroCalendarRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.current().macroNotifications) return Result.success()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val local = LocalDateTime.now(zone)
        val today = LocalDate.now(zone).toEpochDay()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val minute = local.hour * 60 + local.minute
        if (MacroCalendar.isNotifyWindow(minute) && prefs.getLong(KEY_LAST_DAY, Long.MIN_VALUE) != today) {
            val events = MacroCalendar.notificationEvents(calendar.events(), now, zone)
            if (events.isNotEmpty()) {
                notifier.showGas(
                    MACRO_NOTIFICATION_ID,
                    applicationContext.getString(R.string.notification_macro_title),
                    MacroTexts.todayText(applicationContext, events)
                )
            }
            prefs.edit { putLong(KEY_LAST_DAY, today) }
        }
        // Nächster Morgen (an die laufende Aufgabe angehängt, damit sie sich nicht selbst abbricht)
        schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "macro-notify"
        private const val PREFS = "macro_notify"
        private const val KEY_LAST_DAY = "last_day"
        private const val MACRO_NOTIFICATION_ID = 41

        /** Nächsten Lauf um 08:00 Ortszeit planen. */
        fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val now = System.currentTimeMillis()
            val delay = (MacroCalendar.nextNotifyAt(now, ZoneId.systemDefault()) - now).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<MacroNotifyWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
