package com.cryptochecker.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Plant die Hintergrund-Aktualisierung nach einem Neustart bzw. App-Update neu — samt Wecker
 * «Pünktlich prüfen», auch wenn «Wecker und Erinnerungen» gerade erlaubt wurde. Nach Wechsel von Zeitzone
 * oder Uhr nur die Morgen-Meldung «Wirtschaftstermine» (an die Ortszeit gebunden).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: PriceUpdateScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (BackgroundWorkPolicy.replansMacroOn(intent.action)) {
            replanMacro(context)
            return
        }
        if (!BackgroundWorkPolicy.reschedulesOn(intent.action)) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val settings = settingsRepository.current()
                // Android löscht exakte Wecker bei Neustart und Update; nach neuer Berechtigung neu stellen
                scheduler.apply(settings, forceExact = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Einstellungen/WorkManager nicht verfügbar: nur protokollieren, kein Absturz beim Neustart
                Timber.w(e, "Neuplanung nach Neustart fehlgeschlagen")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Zeitzone/Uhr geändert: nur die Morgen-Meldung «Wirtschaftstermine» neu planen, wenn sie eingeschaltet ist. */
    private fun replanMacro(context: Context) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (settingsRepository.current().macroNotifications) {
                    MacroNotifyWorker.rescheduleAfterClockChange(context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Neuplanung nach Zeitzonenwechsel fehlgeschlagen")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
