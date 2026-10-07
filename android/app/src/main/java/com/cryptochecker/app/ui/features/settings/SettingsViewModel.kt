package com.cryptochecker.app.ui.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.notification.AlarmSoundStore
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.service.PriceServiceController
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.tts.SpokenText
import com.cryptochecker.app.tts.TtsSpeaker
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: WatchRepository,
    private val scheduler: PriceUpdateScheduler,
    private val serviceController: PriceServiceController,
    private val ttsSpeaker: TtsSpeaker,
    private val spokenText: SpokenText,
    private val notifier: AppNotifier,
    private val appearanceApplier: AppearanceApplier,
    private val widgetUpdater: WidgetUpdater,
    private val backupManager: com.cryptochecker.app.data.BackupManager,
    private val appLockState: AppLockState,
    private val portfolioSnapshotUpdater: com.cryptochecker.app.widget.PortfolioSnapshotUpdater,
    private val alarmTester: com.cryptochecker.app.notification.AlarmTester,
    private val portfolioRepository: com.cryptochecker.app.data.portfolio.PortfolioRepository,
) : ViewModel() {

    /** «Alarm testen»: echter Alarm über den Weg der Kursalarme, ohne Nachtruhe. */
    suspend fun testAlarm(): com.cryptochecker.app.notification.AlarmTestResult = alarmTester.run()

    /** Ergebnis von Sichern/Wiederherstellen für eine kurze Meldung. */
    private val _backupMessage = MutableStateFlow<BackupMessage?>(null)
    val backupMessage: StateFlow<BackupMessage?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() { _backupMessage.value = null }

    fun exportBackup(uri: android.net.Uri) = update {
        _backupMessage.value = runCatching { backupManager.export(uri) }
            .fold({ BackupMessage.Exported }, { BackupMessage.Failed })
    }

    fun restoreBackup(uri: android.net.Uri) = update {
        // Wer hier wiederherstellt, ist schon drin: Schaltet die Sicherung die Portfolio-Sperre
        // ein, gilt sie erst ab dem nächsten Kaltstart bzw. nach dem Hintergrund-Limit —
        // nicht sofort in dieser Sitzung (vor dem Schreiben, damit nichts aufblitzt).
        // Nur wenn die Sperre bisher aus war: sonst höbe ein Wiederherstellen nach über
        // einer Minute in der Dateiauswahl die gerade fällige Sperre ohne Entsperren auf.
        if (!settingsRepository.current().appLock) appLockState.unlock()
        _backupMessage.value = runCatching { backupManager.restore(uri) }
            .fold(
                onSuccess = { result ->
                    // Neue Einstellungen gleich anwenden
                    val s = settingsRepository.current()
                    scheduler.apply(s)
                    serviceController.apply(s.liveService)
                    appearanceApplier.applyNightMode(s.darkMode)
                    widgetUpdater.updateAll()
                    BackupMessage.Restored(result.watches, result.alarms)
                },
                onFailure = { BackupMessage.Failed }
            )
    }

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _languageTag = MutableStateFlow(AppLanguages.currentTag(context))

    /** Gewählte App-Sprache; leer = Systemsprache. */
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    /** Sprache wechseln; die Fenster bauen sich danach in der neuen Sprache auf. */
    fun setLanguage(tag: String) {
        _languageTag.value = tag
        AppLanguages.select(context, tag)
        viewModelScope.launch { widgetUpdater.updateAll() }
    }

    /** Hell/Dunkel für App und Widgets. */
    /** null = wie das System. */
    fun setDarkMode(dark: Boolean?) = update {
        settingsRepository.setDarkMode(dark)
        appearanceApplier.applyNightMode(dark)
        widgetUpdater.updateAll()
    }

    /**
     * Akzentfarbe: Farbschema, Widgets und Benachrichtigungen sofort.
     * Das App-Icon folgt erst beim Verlassen der App (MainActivity.onStop) —
     * umgeschaltet im laufenden Betrieb schließen manche Android-Versionen
     * sonst die App.
     */
    fun setAccentColor(accent: AccentColor) = update {
        settingsRepository.setAccentColor(accent)
        // Frisch lesen: aktualisiert auch die zwischengespeicherte Farbe der Meldungen
        val settings = settingsRepository.current()
        widgetUpdater.updateAll()
        notifier.refreshColors(watchRepository.getWatches(), ongoing = settings.ongoingNotifications)
    }

    fun unlockDeveloper() = update {
        settingsRepository.setDeveloperUnlocked(true)
    }

    fun setShowHttpLog(show: Boolean) = update {
        settingsRepository.setShowHttpLog(show)
    }

    fun setGasAlertEth(tenths: Int) = update { settingsRepository.setGasAlertEth(tenths) }

    fun setGasAlertBtc(satPerVb: Int) = update { settingsRepository.setGasAlertBtc(satPerVb) }

    /** Alarmton aus der System-Auswahl; null = Standardton. */
    fun setAlarmSound(uri: android.net.Uri?) = update {
        val name = uri?.let { AlarmSoundStore.title(context, it) }
        applyAlarmSound(uri?.toString(), name)
    }

    /** Eigene Audiodatei übernehmen (ab Android 10). Meldet Fehler über [soundError]. */
    fun importAlarmSound(source: android.net.Uri) = update {
        if (!AlarmSoundStore.supportsCustomFile) return@update
        val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AlarmSoundStore.importFile(context, source)
        }
        if (imported == null) {
            _soundError.value = true
        } else {
            applyAlarmSound(imported.first.toString(), imported.second)
        }
    }

    private val _soundError = MutableStateFlow(false)
    val soundError: StateFlow<Boolean> = _soundError.asStateFlow()
    fun clearSoundError() { _soundError.value = false }

    private suspend fun applyAlarmSound(uri: String?, name: String?) {
        val version = settingsRepository.setAlarmSound(uri, name)
        NotificationChannels.ensureAlarmChannel(context, version, uri)
    }

    fun setFearGreedBelow(value: Int) = update {
        settingsRepository.setFearGreedBelow(value)
        scheduler.apply(settingsRepository.current())
    }

    fun setFearGreedAbove(value: Int) = update {
        settingsRepository.setFearGreedAbove(value)
        scheduler.apply(settingsRepository.current())
    }

    fun setZoneAlerts(enabled: Boolean) = update {
        settingsRepository.setZoneAlerts(enabled)
        scheduler.apply(settingsRepository.current())
    }

    fun setActivityAlerts(enabled: Boolean) = update {
        settingsRepository.setActivityAlerts(enabled)
    }

    fun setActivitySensitivity(sensitivity: com.cryptochecker.app.domain.activity.ActivitySensitivity) = update {
        settingsRepository.setActivitySensitivity(sensitivity)
    }

    /** Morgen-Meldung zu Wirtschaftsdaten; plant bzw. entfernt die tägliche Prüfung um 08:00. */
    fun setMacroNotifications(enabled: Boolean) = update {
        settingsRepository.setMacroNotifications(enabled)
        scheduler.apply(settingsRepository.current())
    }

    /** Portfolio-Tab ein/aus; die Daten bleiben beim Ausschalten erhalten. */
    fun setPortfolioEnabled(enabled: Boolean) = update {
        settingsRepository.setPortfolioEnabled(enabled)
    }

    /** Kursfarben (Grün/Rot oder Blau/Orange); Widgets gleich neu zeichnen. */
    fun setPriceColorScheme(scheme: PriceColorScheme) = update {
        settingsRepository.setPriceColorScheme(scheme)
        widgetUpdater.updateAll()
    }

    /** Kursfarben tauschen (Rot = steigend); Widgets gleich neu zeichnen. */
    fun setPriceColorsInverted(inverted: Boolean) = update {
        settingsRepository.setPriceColorsInverted(inverted)
        widgetUpdater.updateAll()
    }

    /** Hoher Kontrast ein/aus; Widgets gleich neu zeichnen. */
    fun setHighContrast(enabled: Boolean) = update {
        settingsRepository.setHighContrast(enabled)
        widgetUpdater.updateAll()
    }

    /** Mini-Chart in der Merkliste ein/aus. */
    fun setWatchlistSparkline(show: Boolean) = update { settingsRepository.setWatchlistSparkline(show) }

    /** Umgerechnete Kurse in der Merkliste («≈ 61’234 CHF»). */
    fun setShowConverted(show: Boolean) = update { settingsRepository.setShowConverted(show) }

    /** Umrechnungswährung (gilt für Merkliste, Alarme und Portfolio). */
    fun setConversionCurrency(currency: String) = update {
        settingsRepository.setPortfolioCurrency(currency)
        // Portfolio-Widget gleich in der neuen Währung (sonst erst nach der nächsten Aktualisierung)
        portfolioSnapshotUpdater.refreshIfWidgets()
    }

    fun setBackgroundUpdates(enabled: Boolean) = update {
        settingsRepository.setBackgroundUpdates(enabled)
        scheduler.apply(settingsRepository.current())
    }

    fun setBackgroundInterval(minutes: Int) = update {
        settingsRepository.setBackgroundInterval(minutes)
        scheduler.apply(settingsRepository.current())
    }

    fun setLiveService(enabled: Boolean) = update {
        settingsRepository.setLiveService(enabled)
        serviceController.apply(enabled)
    }

    fun setLiveInterval(seconds: Int) = update {
        settingsRepository.setLiveInterval(seconds)
        // Der Dienst liest das Intervall bei jedem Durchlauf neu ein.
    }

    fun setIncludeRollingFutures(enabled: Boolean) = update {
        settingsRepository.setIncludeRollingFutures(enabled)
    }

    fun setPriceNotifications(enabled: Boolean) = update {
        settingsRepository.setPriceNotifications(enabled)
        if (!enabled) {
            notifier.cancelAllPrices(watchRepository.getWatches().map { it.id })
        }
    }

    fun setOngoingNotifications(enabled: Boolean) = update {
        settingsRepository.setOngoingNotifications(enabled)
    }

    fun setNotificationChangePercent(percent: Double) = update {
        settingsRepository.setNotificationChangePercent(percent)
    }

    fun setTtsEnabled(enabled: Boolean) = update {
        settingsRepository.setTtsEnabled(enabled)
        if (!enabled) ttsSpeaker.shutdown()
    }

    fun setTtsAlarmsOnly(enabled: Boolean) = update {
        settingsRepository.setTtsAlarmsOnly(enabled)
    }

    fun setSpeechRate(rate: Float) = update {
        settingsRepository.setTtsSpeechRate(rate)
    }

    fun setAlarmCooldown(minutes: Int) = update {
        settingsRepository.setAlarmCooldown(minutes)
    }

    /** Liest den zuletzt bekannten Kurs vor, damit die Stimme prüfbar ist. */
    fun testSpeech() = update {
        val current = settingsRepository.current()
        val watch = watchRepository.getWatches().firstOrNull { it.lastPrice != null }
        val text = if (watch?.lastPrice != null) {
            spokenText.price(watch, watch.lastPrice)
        } else {
            "Crypto Checker"
        }
        ttsSpeaker.speak(text, speechRate = current.ttsSpeechRate, flush = true)
    }

    // ---------------- Nachtruhe ----------------

    fun setQuietHoursEnabled(enabled: Boolean) = update { settingsRepository.setQuietHoursEnabled(enabled) }

    fun setQuietHoursStart(minute: Int) = update { settingsRepository.setQuietHoursStart(minute) }

    fun setQuietHoursEnd(minute: Int) = update { settingsRepository.setQuietHoursEnd(minute) }

    // ---------------- Portfolio-Sperre ----------------

    /** Ausschalten (nach [disableAppLock]); das Portfolio-Widget zeigt danach wieder Werte. */
    fun setAppLock(enabled: Boolean) = update {
        settingsRepository.setAppLock(enabled)
        widgetUpdater.updateAll()
    }

    /**
     * Einschalten erst nach einer erfolgreichen Entsperrung; sonst bleibt die Sperre aus.
     * Die gerade laufende Sitzung gilt danach als entsperrt.
     */
    fun enableAppLock(activity: androidx.fragment.app.FragmentActivity, reason: String) {
        if (appLockState.authenticating) return
        appLockState.authenticating = true
        AppLockAuth.authenticate(activity, reason) { ok ->
            appLockState.authenticating = false
            if (ok) {
                appLockState.unlock()
                setAppLock(true)
            }
        }
    }

    /** Ausschalten verlangt Entsperren, solange das Portfolio gesperrt ist (sonst wäre die Sperre umgangen). */
    fun disableAppLock(activity: androidx.fragment.app.FragmentActivity?, reason: String) =
        gate(activity, reason, PortfolioLockPolicy::disableNeedsUnlock) { setAppLock(false) }

    /** Sichern: Enthält die Datei Portfolio-Daten und ist gesperrt, erst entsperren. */
    fun startBackupExport(activity: androidx.fragment.app.FragmentActivity?, reason: String, launch: () -> Unit) {
        viewModelScope.launch {
            val hasPortfolio = try {
                portfolioRepository.getTransactions().isNotEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            gate(activity, reason, { locked -> PortfolioLockPolicy.backupExportNeedsUnlock(locked, hasPortfolio) }, launch)
        }
    }

    /** Wiederherstellen: solange gesperrt, erst entsperren (die Sicherung kann die Sperre ausschalten). */
    fun startRestore(activity: androidx.fragment.app.FragmentActivity?, reason: String, launch: () -> Unit) =
        gate(activity, reason, PortfolioLockPolicy::restoreNeedsUnlock, launch)

    /** [onUnlocked] sofort, wenn [needsUnlock] für den aktuellen Sperr-Zustand false ist; sonst nach der Abfrage. */
    private fun gate(
        activity: androidx.fragment.app.FragmentActivity?,
        reason: String,
        needsUnlock: (Boolean) -> Boolean,
        onUnlocked: () -> Unit,
    ) {
        viewModelScope.launch {
            val locked = PortfolioLockPolicy.isLocked(settingsRepository.current().appLock, appLockState.lockRequested.value)
            appLockState.requireUnlock(activity, needsUnlock(locked), reason, onUnlocked)
        }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

sealed interface BackupMessage {
    data object Exported : BackupMessage
    data class Restored(val watches: Int, val alarms: Int) : BackupMessage
    data object Failed : BackupMessage
}
