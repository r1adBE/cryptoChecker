package com.cryptochecker.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.PortfolioDataCheck
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

    /** Lazy: nur, wenn ein abgebrochenes Wiederherstellen zurückzurollen ist ([RestoreJournal]). */
    @Inject lateinit var backupManager: dagger.Lazy<com.cryptochecker.app.data.BackupManager>

    /** Lazy: nur, wenn nach neu angelegten Einstellungen Portfolio-Daten zu prüfen sind ([PortfolioDataCheck]). */
    @Inject lateinit var portfolioAlarmRepository: dagger.Lazy<PortfolioAlarmRepository>

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
        // unverschlüsselte Kopie der Merkliste entfernen (sie läge sonst offen im App-Ordner)
        if (getDatabasePath(com.cryptochecker.app.data.local.SystemBackupAgent.EXPORT_NAME).exists()) {
            runCatching { com.cryptochecker.app.data.local.DatabaseSystemBackup(this).cleanUpLeftover() }
                .onFailure { Timber.w(it, "Systemsicherung: Aufräumen fehlgeschlagen") }
        }
        // Ebenso eine unverschlüsselte Portfolio-Kopie neben der Portfolio-Datenbank (dauernd bis Build 75, oder eine
        // abgebrochene Sicherung); ohne Datenbank bleibt sie für die Übernahme nach einer Wiederherstellung
        runCatching { PortfolioBackupMirror.cleanUpLeftover(this) }
            .onFailure { Timber.w(it, "Portfolio: Kopie für die Systemsicherung nicht aufgeräumt") }
        super.onCreate()

        AppLanguages.restoreForActivities(this)
        AppLanguages.applyNumberLocale()
        // Uhrzeiten (PriceFormat.time, MomentText) nach der 12/24-h-Einstellung des Systems
        com.cryptochecker.app.util.ClockText.install(this)

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

        // Abgebrochenes Wiederherstellen (Journal liegt noch da): Stand davor zurückschreiben, Hinweis vormerken
        if (java.io.File(noBackupFilesDir, com.cryptochecker.app.data.RestoreJournal.FILE_NAME).exists()) {
            scope.launch { backupManager.get().recoverInterruptedRestore() }
        }

        // Einmalig: alter Bestand aus der Merkliste wird zu Käufen im Portfolio
        scope.launch { portfolioRepository.migrateHoldingsOnce() }

        // Einstellungen neu angelegt (z. B. Datei beschädigt), Portfolio-Daten aber da: Portfolio wieder an
        scope.launch {
            // Nur leer im Arbeitsspeicher (Schlüssel nicht lesbar): nicht als «keine Daten» entscheiden
            PortfolioDataCheck.resolve(this@CryptoCheckerApp, portfolioInMemory = { PortfolioDatabase.inMemory() }) {
                portfolioRepository.hasData() || portfolioAlarmRepository.get().getAll().isNotEmpty()
            }
        }

        // «Portfolio in Systemsicherung»: Merker für den Sicherungs-Agenten nachführen (er schreibt die Kopie nur während
        // einer Sicherung); aus → Merker und eine noch liegende Kopie weg (auch bei jedem Start)
        scope.launch {
            settingsRepository.settings
                // Android 8/8.1: Schalter wirkungslos, Kopie wird gelöscht (requireFlags dort unbekannt)
                .map { PortfolioLockPolicy.systemBackupEffective(it.portfolioSystemBackup, Build.VERSION.SDK_INT) }
                .distinctUntilChanged()
                .catch { Timber.w(it, "Portfolio: Kopie für die Systemsicherung angehalten") }
                .collectLatest { allowed ->
                    PortfolioBackupMirror.setEnabled(this@CryptoCheckerApp, allowed)
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
