package com.cryptochecker.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.lock.PortfolioLockPolicy
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Lazy: Kopie (und Portfolio-Datenbank) nur, wenn «Portfolio in Systemsicherung» an ist. */
    @Inject lateinit var portfolioBackupMirror: dagger.Lazy<PortfolioBackupMirror>

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

    /** Sprach- oder Regionswechsel: Android setzt `Locale.setDefault` neu — Zahlen-Locale nachziehen. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguages.applyNumberLocale()
    }

    override fun onCreate() {
        // Vor Hilt (öffnet evtl. schon die Datenbank): eine bei abgebrochener Systemsicherung liegengebliebene,
        // unverschlüsselte Kopie der Merkliste entfernen (Review 22)
        if (getDatabasePath(com.cryptochecker.app.data.local.SystemBackupAgent.EXPORT_NAME).exists()) {
            runCatching { com.cryptochecker.app.data.local.DatabaseSystemBackup(this).cleanUpLeftover() }
                .onFailure { Timber.w(it, "Systemsicherung: Aufräumen fehlgeschlagen") }
        }
        super.onCreate()

        AppLanguages.restoreForActivities(this)
        AppLanguages.applyNumberLocale()

        if (BuildConfig.DEBUG) Timber.plant(DebugTree())

        // Phemex: Faktoren (Ep/Ev) der Produktliste auf dem Gerät — nach einem Neustart nie ein geratener Faktor
        com.cryptochecker.marketdata.model.market.PhemexScales.store =
            com.cryptochecker.marketdata.model.market.PhemexScales.FileStore(java.io.File(noBackupFilesDir, "phemex_scales.json"))

        NotificationChannels.createAll(this)

        applyAppearance()

        scope.launch {
            val settings = settingsRepository.current()
            NotificationChannels.ensureAlarmChannel(this@CryptoCheckerApp, settings.alarmChannelVersion, settings.alarmSoundUri)
            scheduler.apply(settings)
            // Danach Bedarf verfolgen: Paare, Alarme, Widgets an/aus → periodische Aktualisierung planen/absagen
            scheduler.followWork()
        }

        // Einmalig: alter Bestand aus der Merkliste wird zu Käufen im Portfolio
        scope.launch { portfolioRepository.migrateHoldingsOnce() }

        // «Portfolio in Systemsicherung»: Kopie nachführen nur, solange der Schalter an ist; aus → Kopie
        // löschen (auch bei jedem Start). Wechselt der Schalter, wird die Kopie gestartet bzw. beendet.
        scope.launch {
            settingsRepository.settings
                // Android 8/8.1: Schalter wirkungslos, Kopie wird gelöscht (requireFlags dort unbekannt)
                .map { PortfolioLockPolicy.systemBackupEffective(it.portfolioSystemBackup, Build.VERSION.SDK_INT) }
                .distinctUntilChanged()
                .catch { Timber.w(it, "Portfolio: Kopie für die Systemsicherung angehalten") }
                .collectLatest { allowed ->
                    if (allowed) portfolioBackupMirror.get().run()
                    else PortfolioBackupMirror.deleteCopy(this@CryptoCheckerApp)
                }
        }

        Timber.i("Crypto Checker gestartet")
    }

    /**
     * Vor dem ersten Fenster, sonst blitzt kurz das falsche Hell/Dunkel auf.
     * Gelesen wird der kleine Start-Zwischenspeicher (SharedPreferences) statt DataStore —
     * nie runBlocking auf dem Hauptthread (ANR-Gefahr bei langsamem Speicher).
     * Danach im Hintergrund mit den Einstellungen abgleichen; das schreibt auch den Zwischenspeicher.
     *
     * Fehlt er (Update von einem älteren Build, nach Wiederherstellung), startet die App wie das
     * System und übernimmt die gespeicherte Wahl, sobald sie gelesen ist. Abwägung: Weicht die Wahl
     * vom System ab und ist das erste Fenster dann schon offen, baut AppCompat es einmal neu auf
     * (kurzes Umschalten) — nur bei diesem einen Start, danach liegt der Zwischenspeicher vor.
     *
     * Ohne eigene Wahl («Wie das System») folgt die App dem System.
     */
    private fun applyAppearance() {
        val cached = appearanceApplier.cachedNightMode()
        // Fehlt der Zwischenspeicher: nichts setzen = AppCompat folgt dem System
        val startDark = when (cached) {
            is AppearanceApplier.CachedNightMode.Known -> cached.dark.also { appearanceApplier.applyNightMode(it) }
            AppearanceApplier.CachedNightMode.Missing -> null
        }
        scope.launch {
            val dark = settingsRepository.current().darkMode
            // Ohne Zwischenspeicher immer anwenden (schreibt ihn), sonst nur bei Abweichung
            if (cached is AppearanceApplier.CachedNightMode.Missing || dark != startDark) {
                withContext(Dispatchers.Main) { appearanceApplier.applyNightMode(dark) }
            }
        }
        // Das App-Icon wird nicht hier umgeschaltet, sondern erst beim
        // Verlassen der App (MainActivity.onStop) — beim Start könnte das
        // den gerade laufenden Aufruf über das alte Icon abbrechen.
    }
}
