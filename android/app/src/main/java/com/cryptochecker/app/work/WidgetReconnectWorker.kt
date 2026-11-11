package com.cryptochecker.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.widget.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Netz wieder da, nachdem ein Widget «Keine Verbindung» gezeichnet hat ([com.cryptochecker.app.widget.WidgetConnectivity]):
 * sofort ohne Netz neu zeichnen (der Hinweis verschwindet), dann die Kurse holen wie die Hintergrund-Aktualisierung
 * ([BackgroundCheck], zeichnet danach selbst; eben erst geprüft: übersprungen). Einmaliger Auftrag mit der Bedingung
 * CONNECTED — kein dauernder Dienst, kein Rückruf über die Lebensdauer des Prozesses.
 *
 * CONNECTED heisst nur «ein Netz ist da»; ohne bestätigten Zugang (Captive Portal vor dem Login) gilt das Gerät für
 * die App weiter als offline: dann mit Backoff später erneut, statt sofort wieder anzulaufen.
 */
@HiltWorker
class WidgetReconnectWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val connectivity: ConnectivityMonitor,
    private val widgetUpdater: WidgetUpdater,
    private val backgroundCheck: BackgroundCheck,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!connectivity.isOnlineNow()) return Result.retry()
        runCatchingCancellable { widgetUpdater.updateAll(fetch = false) }
            .onFailure { Timber.w(it, "Widgets nach Rückkehr des Netzes nicht neu gezeichnet") }
        runCatchingCancellable {
            backgroundCheck.run(CheckTrigger.WORKER, networkGuaranteed = true, ttsWaitMillis = TTS_WAIT_MILLIS)
        }.onFailure { Timber.d(it, "Kurse nach Rückkehr des Netzes nicht geholt; nächster Lauf im normalen Takt") }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "widget-reconnect"

        /** Wie [PriceUpdateWorker]: höchstens so lange auf Ansagen des Durchlaufs warten. */
        private const val TTS_WAIT_MILLIS = 15_000L

        /** Einmal planen; ein schon wartender oder laufender Auftrag bleibt (KEEP). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetReconnectWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BackgroundWorkPolicy.RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request) }
                .onFailure { Timber.w(it, "Neuzeichnen bei Rückkehr des Netzes nicht geplant") }
        }
    }
}
