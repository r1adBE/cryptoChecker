package com.cryptochecker.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cryptochecker.app.data.backup.AutoBackupRunner
import com.cryptochecker.app.domain.backup.AutoBackupPolicy
import com.cryptochecker.app.domain.work.countedRun
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Automatische Sicherung ohne geöffnete App: WorkManager weckt alle [INTERVAL_HOURS] Stunden, gesichert wird nur, wenn
 * die Sicherung fällig ist ([AutoBackupPolicy.isDue]: sieben Tage nach der letzten, nach einem Fehlschlag frühestens
 * nach sechs Stunden). Ein Fehler steht im Zustand der Sicherung ([AutoBackupRunner]); die Aufgabe selbst gilt als
 * erledigt, damit WorkManager nicht zusätzlich mit eigenem Rückzug wiederholt. Wie iOS `AutoBackupTask`.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: AutoBackupRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = countedRun {
        runner.runIfDue()
        Result.success()
    }

    companion object {
        const val WORK_NAME = "auto-backup"
        const val INTERVAL_HOURS = 6L

        /** Eingeschaltet: Aufgabe planen (eine bestehende bleibt); ausgeschaltet: entfernen. */
        fun sync(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
