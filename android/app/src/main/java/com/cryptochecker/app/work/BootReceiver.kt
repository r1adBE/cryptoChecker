package com.cryptochecker.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Plant die Hintergrund-Aktualisierung nach einem Neustart bzw. App-Update neu — samt Wecker
 * «Pünktlich prüfen», auch wenn «Wecker und Erinnerungen» gerade erlaubt wurde. Nach Wechsel von Zeitzone
 * oder Uhr die Morgen-Meldung «Wirtschaftstermine» (an die Ortszeit gebunden) und den Wecker abgleichen.
 * Über [launchAsync]: Zeitgrenze nach goAsync ([com.cryptochecker.app.util.ReceiverAsync.TIMEOUT_MILLIS]),
 * Fehler nur protokolliert, finish() immer.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: PriceUpdateScheduler

    @Inject lateinit var exactScheduler: ExactCheckScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (BackgroundWorkPolicy.replansMacroOn(intent.action) || BackgroundWorkPolicy.resyncsExactOn(intent.action)) {
            onClockChanged(context, intent.action)
            return
        }
        if (!BackgroundWorkPolicy.reschedulesOn(intent.action)) return

        // Einstellungen/WorkManager nicht verfügbar: nur protokollieren, kein Absturz beim Neustart
        launchAsync("Neuplanung nach Neustart") {
            val settings = settingsRepository.current()
            // Android löscht exakte Wecker bei Neustart und Update; nach neuer Berechtigung neu stellen
            scheduler.apply(settings, forceExact = true)
        }
    }

    /**
     * Zeitzone/Uhr geändert: Morgen-Meldung «Wirtschaftstermine» neu planen (wenn eingeschaltet) und den Wecker
     * «Pünktlich prüfen» abgleichen — nach zurückgestellter Uhr stünde er sonst weit in der Zukunft.
     */
    private fun onClockChanged(context: Context, action: String?) {
        launchAsync("Neuplanung nach Zeitzonenwechsel") {
            val settings = settingsRepository.current()
            if (BackgroundWorkPolicy.resyncsExactOn(action)) exactScheduler.sync(settings.exactChecks)
            if (BackgroundWorkPolicy.replansMacroOn(action) && settings.macroNotifications) {
                MacroNotifyWorker.rescheduleAfterClockChange(context)
            }
        }
    }
}
