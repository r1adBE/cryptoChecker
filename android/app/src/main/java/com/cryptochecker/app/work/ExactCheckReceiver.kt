package com.cryptochecker.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.refresh.CheckBudget
import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.ConnectivityMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Wecker «Pünktlich prüfen» ([ExactCheckScheduler]) ist abgelaufen: zuerst den nächsten stellen
 * (die Kette reisst auch bei Fehlern nicht ab), dann dieselbe Prüfung wie WorkManager
 * ([BackgroundCheck]) per [goAsync], höchstens [TIMEOUT_MILLIS] — je Börse mit eigenem Budget
 * ([com.cryptochecker.app.domain.refresh.CheckBudget]), damit eine langsame Börse den Lauf nicht
 * leer abbrechen lässt (Kurse, Alarme der übrigen kommen durch). Android lässt die App nach einem
 * allow-while-idle-Wecker kurz ins Netz, auch im Doze-Modus — dort nur rund 10 s: Im Ruhemodus
 * enden die Abfragen darum nach [CheckBudget.IDLE_NETWORK_MILLIS] ([CheckBudget.exactTimeoutMillis]).
 * Nicht exportiert: nur der eigene PendingIntent erreicht ihn.
 */
@AndroidEntryPoint
class ExactCheckReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: ExactCheckScheduler

    @Inject lateinit var backgroundCheck: BackgroundCheck

    @Inject lateinit var connectivity: ConnectivityMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return

        val pendingResult = goAsync()
        // Ruhemodus jetzt (beim Auslösen) bestimmt das Budget: das Netz-Fenster beginnt mit dem Wecker
        val deviceIdle = runCatching { context.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true }
            .getOrDefault(false)
        val timeoutMillis = CheckBudget.exactTimeoutMillis(deviceIdle, TIMEOUT_MILLIS, DEADLINE_MARGIN_MILLIS)
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val enabled = settingsRepository.current().exactChecks
                val hasWork = backgroundCheck.hasWork()
                val permitted = scheduler.canScheduleExact()
                // Netz frisch abfragen (der Prozess kann eingefroren gewesen sein) — nur wenn es zählt
                val online = BackgroundSchedule.shouldArmExact(enabled, permitted, hasWork) && connectivity.isOnlineNow()
                when (BackgroundWorkPolicy.firedAction(enabled, permitted, hasWork, online)) {
                    // Aus, keine Berechtigung mehr oder keine Paare: absagen, nicht neu stellen
                    BackgroundWorkPolicy.FiredAction.CANCEL -> {
                        scheduler.sync(enabled, hasWork)
                        return@launch
                    }
                    // Offline: kein Versuch — sonst stünden alle Paare auf Fehler. WorkManager holt
                    // nach, sobald Netz da ist.
                    BackgroundWorkPolicy.FiredAction.REARM_SKIP_OFFLINE -> {
                        scheduler.rearm()
                        backgroundCheck.log(CheckTrigger.EXACT, CheckResult.SKIPPED, startedAt)
                        return@launch
                    }
                    BackgroundWorkPolicy.FiredAction.REARM_AND_CHECK -> scheduler.rearm()
                }
                // Frist für die Prüfung selbst (etwas vor der harten Grenze): je Börse ein Budget,
                // danach wird gespeichert und ausgewertet, was da ist (CheckBudget)
                val deadlineAt = startedAt + timeoutMillis - DEADLINE_MARGIN_MILLIS
                val done = withTimeoutOrNull(timeoutMillis - (System.currentTimeMillis() - startedAt)) {
                    backgroundCheck.run(
                        CheckTrigger.EXACT, networkGuaranteed = true, ttsWaitMillis = TTS_WAIT_MILLIS, deadlineAt = deadlineAt,
                    )
                    true
                }
                if (done == null) Timber.d("Pünktliche Prüfung: Zeitgrenze erreicht")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isDatabaseKeyUnavailable()) {
                    // Datenbank-Schlüssel gerade nicht lesbar: Lauf überspringen, Kette nicht abreissen lassen
                    // (beim nächsten Wecker erneut; liefe der Fehler vor dem Neustellen, stünde sonst keiner)
                    Timber.d(e, "Pünktliche Prüfung: Datenbank-Schlüssel nicht verfügbar, später erneut")
                    runCatching { if (settingsRepository.current().exactChecks) scheduler.rearm() }
                } else {
                    Timber.w(e, "Pünktliche Prüfung fehlgeschlagen")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.cryptochecker.app.action.EXACT_CHECK"

        /**
         * Höchstens so lange läuft die Prüfung im Empfänger (Android erlaubt im Hintergrund rund 60 s);
         * im Ruhemodus kürzer, siehe [CheckBudget.exactTimeoutMillis] (15 s, davon 9 s Netz).
         */
        private const val TIMEOUT_MILLIS = 20_000L

        /** Ansagen höchstens so lange abwarten (innerhalb der Zeitgrenze). */
        private const val TTS_WAIT_MILLIS = 5_000L

        /** Die Prüfung plant mit dieser Reserve vor [TIMEOUT_MILLIS] (die harte Grenze bleibt als Schutz). */
        private const val DEADLINE_MARGIN_MILLIS = 1_000L
    }
}
