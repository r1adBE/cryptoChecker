package com.cryptochecker.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cryptochecker.app.R
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.service.LiveServiceGate
import com.cryptochecker.app.tts.TtsSpeaker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/** Aktualisiert Kurse im Hintergrund, auch wenn die App geschlossen ist. */
@HiltWorker
class PriceUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val priceRefresher: PriceRefresher,
    private val notifier: AppNotifier,
    private val refreshStats: RefreshStats,
    private val ttsSpeaker: TtsSpeaker,
) : CoroutineWorker(appContext, params) {

    /**
     * Pflicht für bevorzugte Aufgaben: Bis Android 11 laufen sie als
     * Vordergrunddienst und brauchen eine Benachrichtigung. Fehlt sie,
     * scheitert die Aufgabe dort.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.refreshNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                AppNotifier.REFRESH_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(AppNotifier.REFRESH_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, 0L)
        val waitedMillis = if (enqueuedAt > 0) System.currentTimeMillis() - enqueuedAt else -1L

        // Läuft der Live-Dienst, macht er dieselbe Arbeit schon alle 15 – 300 s:
        // den periodischen Durchlauf auslassen (ein Knopfdruck läuft immer)
        if (LiveServiceGate.skipWorker(manual, LiveServiceGate.lastBeatAt, System.currentTimeMillis())) {
            Timber.d("Hintergrund-Aktualisierung ausgelassen: Live-Dienst läuft")
            return Result.success()
        }

        return try {
            // Portfolio-Widget im Job abwarten (nicht losgelöst), siehe PriceRefresher.refreshAll
            val summary = priceRefresher.refreshAll(awaitPortfolioSnapshot = true)
            // Ansagen dieses Durchlaufs laufen in einer Warteschlange; im Job abwarten (höchstens
            // 10 s), sonst kann Android den Prozess vorher beenden. Ohne Ansagen sofort weiter.
            if (!ttsSpeaker.awaitQueued(TTS_WAIT_MILLIS)) Timber.d("Ansagen nach %d ms noch nicht abgesetzt", TTS_WAIT_MILLIS)

            // Zeit zwischen Knopfdruck und Start: Die zählt nicht zur Dauer,
            // verlängert aber, wie lange der Kreis in der App dreht.
            if (manual && waitedMillis >= 0) {
                refreshStats.prependToReport(
                    applicationContext.getString(
                        R.string.refresh_report_wait,
                        "%.1f s".format(java.util.Locale.ROOT, waitedMillis / 1000.0)
                    )
                )
            }
            Timber.d(
                "Hintergrund-Aktualisierung: %d geprüft, %d fehlgeschlagen, %d Alarme",
                summary.checked, summary.failed, summary.alarmsTriggered
            )

            // Nur wenn gar nichts geklappt hat, lohnt ein zweiter Versuch.
            // Nicht bei einem Knopfdruck: Der soll sofort fertig sein, sonst
            // dreht die Anzeige in der App bis zum nächsten Versuch weiter.
            if (!manual && summary.checked > 0 && summary.failed == summary.checked) Result.retry()
            else Result.success()
        } catch (ex: Exception) {
            Timber.w(ex, "Hintergrund-Aktualisierung fehlgeschlagen")
            if (manual) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "price-update-periodic"
        const val ONE_TIME_WORK_NAME = "price-update-now"

        /** Vom Nutzer angestoßen (Knopf in App oder Widget). */
        const val KEY_MANUAL = "manual"

        /** Zeitpunkt des Knopfdrucks, um die Wartezeit bis zum Start zu messen. */
        const val KEY_ENQUEUED_AT = "enqueued_at"

        /** So lange wartet der Job höchstens auf die Ansagen des Durchlaufs. */
        private const val TTS_WAIT_MILLIS = 10_000L
    }
}
