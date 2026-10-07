package com.cryptochecker.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Plant die Hintergrund-Aktualisierung nach einem Neustart bzw. App-Update neu. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: PriceUpdateScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val settings = settingsRepository.current()
                scheduler.apply(settings)
                // Den Live-Dienst hier nicht starten: Ab Android 15 darf ein dataSync-
                // Vordergrunddienst nicht aus BOOT_COMPLETED starten, und auch sonst kommt der
                // Start aus dem Hintergrund. Er startet wieder, wenn der Nutzer die App öffnet
                // (MainActivity.onStart); bis dahin läuft die geplante Hintergrund-Aktualisierung.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
