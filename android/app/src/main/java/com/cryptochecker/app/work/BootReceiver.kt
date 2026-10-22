package com.cryptochecker.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.appScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Plant die Hintergrund-Aktualisierung nach einem Neustart bzw. App-Update neu — samt Wecker
 * «Pünktlich prüfen», auch wenn «Wecker und Erinnerungen» gerade erlaubt wurde. Nach Wechsel von Zeitzone
 * oder Uhr die Morgen-Meldung «Wirtschaftstermine» (an die Ortszeit gebunden) und den Wecker abgleichen.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: PriceUpdateScheduler

    @Inject lateinit var exactScheduler: ExactCheckScheduler

    private val scope = appScope(Dispatchers.Default, "BootReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        if (BackgroundWorkPolicy.replansMacroOn(intent.action) || BackgroundWorkPolicy.resyncsExactOn(intent.action)) {
            onClockChanged(context, intent.action)
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

    /**
     * Zeitzone/Uhr geändert: Morgen-Meldung «Wirtschaftstermine» neu planen (wenn eingeschaltet) und den Wecker
     * «Pünktlich prüfen» abgleichen — nach zurückgestellter Uhr stünde er sonst weit in der Zukunft.
     */
    private fun onClockChanged(context: Context, action: String?) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                val settings = settingsRepository.current()
                if (BackgroundWorkPolicy.resyncsExactOn(action)) exactScheduler.sync(settings.exactChecks)
                if (BackgroundWorkPolicy.replansMacroOn(action) && settings.macroNotifications) {
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
