package com.cryptochecker.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cryptochecker.app.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Plant die Hintergrund-Aktualisierung. */
@Singleton
class PriceUpdateScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(intervalMinutes: Int) {
        val minutes = intervalMinutes
            .coerceAtLeast(AppSettings.MIN_BACKGROUND_INTERVAL_MINUTES)
            .toLong()

        val request = PeriodicWorkRequestBuilder<PriceUpdateWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PriceUpdateWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Timber.d("Hintergrund-Aktualisierung alle %d Minuten geplant", minutes)
    }

    fun cancelPeriodic() {
        workManager.cancelUniqueWork(PriceUpdateWorker.PERIODIC_WORK_NAME)
    }

    /** Einmalige sofortige Aktualisierung, z. B. aus dem Widget heraus. */
    fun refreshNow() {
        val request = OneTimeWorkRequestBuilder<PriceUpdateWorker>()
            .setConstraints(constraints)
            // Bevorzugt ausführen, damit der Knopf im Widget spürbar reagiert.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    PriceUpdateWorker.KEY_MANUAL to true,
                    PriceUpdateWorker.KEY_ENQUEUED_AT to System.currentTimeMillis(),
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            PriceUpdateWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * true, solange eine angestoßene Aktualisierung (Widget-Knopf) wartet oder läuft. Der Knopf
     * in der App läuft direkt (ManualRefresh), nicht mehr über WorkManager.
     */
    fun observeManualRefreshRunning(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(PriceUpdateWorker.ONE_TIME_WORK_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }
            .distinctUntilChanged()

    fun apply(settings: AppSettings) {
        if (settings.backgroundUpdates) schedulePeriodic(settings.backgroundIntervalMinutes)
        else cancelPeriodic()

        // Marktphasen- und Fear-&-Greed-Meldung: zweimal täglich prüfen
        if (settings.zoneAlerts || settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0) {
            val request = PeriodicWorkRequestBuilder<ZoneCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                ZoneCheckWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            workManager.cancelUniqueWork(ZoneCheckWorker.WORK_NAME)
        }

        // Wirtschaftstermine: Morgen-Meldung täglich um 08:00 Ortszeit
        if (settings.macroNotifications) MacroNotifyWorker.schedule(context) else MacroNotifyWorker.cancel(context)
    }
}
