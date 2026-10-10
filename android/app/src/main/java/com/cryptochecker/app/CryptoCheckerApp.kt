package com.cryptochecker.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import timber.log.Timber.DebugTree
import javax.inject.Inject

@HiltAndroidApp
class CryptoCheckerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: PriceUpdateScheduler

    @Inject lateinit var appearanceApplier: AppearanceApplier

    @Inject lateinit var portfolioRepository: PortfolioRepository

    @Inject lateinit var portfolioBackupMirror: PortfolioBackupMirror

    /**
     * Hintergrundarbeit des Prozesses. Fehler (Datenbank, Datei, WorkManager) nur protokollieren:
     * Ohne Handler würde eine Ausnahme in einem dieser Jobs den ganzen Prozess beenden.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Timber.w(e, "Hintergrundaufgabe beim Start fehlgeschlagen") }
    )

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    /** Bis Android 12 die gewählte App-Sprache auch für den App-Kontext setzen. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguages.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()

        AppLanguages.restoreForActivities(this)

        if (BuildConfig.DEBUG) Timber.plant(DebugTree())

        NotificationChannels.createAll(this)

        applyAppearance()

        scope.launch {
            val settings = settingsRepository.current()
            NotificationChannels.ensureAlarmChannel(this@CryptoCheckerApp, settings.alarmChannelVersion, settings.alarmSoundUri)
            scheduler.apply(settings)
            // Den Live-Dienst (Vordergrunddienst) hier NICHT starten: Der Prozess startet auch
            // im Hintergrund (Neustart, WorkManager, Widget), wo Android ab 12 bzw. 15 den Start
            // ablehnt. Er startet nur mit sichtbarer App (MainActivity.onStart, Einstellungen).
        }

        // Einmalig: alter Bestand aus der Merkliste wird zu Käufen im Portfolio
        scope.launch { portfolioRepository.migrateHoldingsOnce() }

        // «Portfolio in Systemsicherung»: Kopie schreiben (an) bzw. löschen (aus), solange der Prozess läuft
        scope.launch { portfolioBackupMirror.run() }

        Timber.i("Crypto Checker gestartet")
    }

    /**
     * Synchron vor dem ersten Fenster, sonst blitzt kurz das falsche
     * Hell/Dunkel auf. Der Zugriff liest eine winzige Datei und ist schnell.
     *
     * Ohne eigene Wahl («Wie das System») folgt die App dem System.
     */
    private fun applyAppearance() {
        val settings = runBlocking { settingsRepository.current() }
        appearanceApplier.applyNightMode(settings.darkMode)
        // Das App-Icon wird nicht hier umgeschaltet, sondern erst beim
        // Verlassen der App (MainActivity.onStop) — beim Start könnte das
        // den gerade laufenden Aufruf über das alte Icon abbrechen.
    }
}
