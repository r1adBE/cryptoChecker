package com.cryptochecker.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.refresh.RetryWait
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.refreshNotification
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.ListWidgetRetry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Aktualisiert Kurse im Hintergrund, auch wenn die App geschlossen ist. */
@HiltWorker
class PriceUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backgroundCheck: BackgroundCheck,
    private val notifier: AppNotifier,
    private val refreshStats: RefreshStats,
    private val exactScheduler: ExactCheckScheduler,
    private val settingsRepository: SettingsRepository,
    private val widgetRetry: ListWidgetRetry,
) : CoroutineWorker(appContext, params) {

    /**
     * Pflicht für bevorzugte Aufgaben: Bis Android 11 laufen sie als
     * Vordergrunddienst und brauchen eine Benachrichtigung. Fehlt sie,
     * scheitert die Aufgabe dort. Ohne Diensttyp: Die App deklariert
     * keinen Vordergrunddienst-Typ (kein dataSync); ab Android 12 läuft die
     * bevorzugte Aufgabe als Job und ruft das hier nicht auf.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(AppNotifier.REFRESH_NOTIFICATION_ID, notifier.refreshNotification())

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, 0L)
        val waitedMillis = if (enqueuedAt > 0) System.currentTimeMillis() - enqueuedAt else -1L
        // Neuer Versuch nach «Datenbank-Schlüssel nicht lesbar» beginnt: Countdown im Widget weg
        if (manual && runAttemptCount > 0) widgetRetry.clear()

        return try {
            // Gemeinsamer Weg mit dem exakten Wecker (BackgroundCheck): überspringt geplante Läufe kurz
            // nach einer Prüfung, wartet auf Ansagen (höchstens 15 s) und vermerkt «Letzte Prüfungen».
            // Netz garantiert die Bedingung CONNECTED (kein Überspringen wegen veraltetem «offline»).
            val trigger = if (manual) CheckTrigger.WIDGET else CheckTrigger.WORKER
            val summary = backgroundCheck.run(trigger, networkGuaranteed = true, ttsWaitMillis = TTS_WAIT_MILLIS)
            // Netz unter dem Wecker «Pünktlich prüfen»: fehlt er (z. B. Paare neu hinzugefügt), neu stellen
            if (BackgroundWorkPolicy.syncsExactAfterRun(manual)) syncExact()
            if (summary == null) return BackgroundWorkPolicy.outcomeAfterRun.toResult()

            // Zeit zwischen Knopfdruck und Start (samt Wartezeit früherer Versuche): Die zählt nicht zur Dauer,
            // verlängert aber, wie lange der Kreis in der App dreht. Dazu die Nummer des Versuchs für den Bericht.
            if (manual && waitedMillis >= 0) {
                refreshStats.setWait(waitedMillis, RetryWait.attemptNumber(runAttemptCount))
            }
            Timber.d(
                "Hintergrund-Aktualisierung: %d geprüft, %d fehlgeschlagen, %d Alarme",
                summary.checked, summary.failed, summary.alarmsTriggered
            )

            if (BackgroundWorkPolicy.allFailed(summary.checked, summary.failed)) {
                Timber.w("Hintergrund-Aktualisierung: alle %d Paare fehlgeschlagen, nächster Lauf im normalen Takt", summary.checked)
            }
            // Kein Retry, auch wenn alles scheiterte: Der Backoff verschöbe den nächsten Lauf um Stunden
            BackgroundWorkPolicy.outcomeAfterRun.toResult()
        } catch (ex: CancellationException) {
            // Abbruch (Bedingung weg, Auftrag ersetzt/abgebrochen) nicht als Fehlschlag melden
            throw ex
        } catch (ex: Exception) {
            // Datenbank-Schlüssel gerade nicht lesbar (Keystore): Lauf überspringen, WorkManager versucht es später
            if (ex.isDatabaseKeyUnavailable()) {
                Timber.d(ex, "Hintergrund-Aktualisierung: Datenbank-Schlüssel nicht verfügbar, später erneut")
                // Aus dem Widget: dort bis zum nächsten Versuch herunterzählen (die App liest ihn aus der WorkInfo)
                if (manual) {
                    val backoffMillis = BackgroundWorkPolicy.RETRY_BACKOFF_SECONDS * 1000
                    val now = System.currentTimeMillis()
                    widgetRetry.show(RetryWait.linearRetryAt(now, backoffMillis, runAttemptCount))
                }
                return BackgroundWorkPolicy.outcomeOnKeyUnavailable().toResult()
            }
            Timber.w(ex, "Hintergrund-Aktualisierung fehlgeschlagen")
            if (BackgroundWorkPolicy.syncsExactAfterRun(manual)) syncExact()
            BackgroundWorkPolicy.outcomeOnError(manual).toResult()
        }
    }

    /** Wecker «Pünktlich prüfen» abgleichen (stellt nur, wenn keiner gestellt ist); Fehler bleiben still. */
    private suspend fun syncExact() {
        runCatchingCancellable {
            exactScheduler.sync(settingsRepository.current().exactChecks, hasWork = backgroundCheck.hasWork())
        }
    }

    private fun BackgroundWorkPolicy.WorkOutcome.toResult(): Result = when (this) {
        BackgroundWorkPolicy.WorkOutcome.SUCCESS -> Result.success()
        BackgroundWorkPolicy.WorkOutcome.RETRY -> Result.retry()
        BackgroundWorkPolicy.WorkOutcome.FAILURE -> Result.failure()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "price-update-periodic"
        const val ONE_TIME_WORK_NAME = "price-update-now"

        /** Vom Nutzer angestossen (Knopf in App oder Widget). */
        const val KEY_MANUAL = "manual"

        /** Zeitpunkt des Knopfdrucks, um die Wartezeit bis zum Start zu messen. */
        const val KEY_ENQUEUED_AT = "enqueued_at"

        /**
         * So lange wartet der Job höchstens auf die Ansagen des Durchlaufs — bis die letzte gesprochen
         * ist (nicht nur übergeben): kalter Start der Engine (bis 8 s) plus eine Alarm-Ansage.
         */
        private const val TTS_WAIT_MILLIS = 15_000L
    }
}
