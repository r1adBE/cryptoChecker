package com.cryptochecker.app.ui.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    internal val settingsRepository: SettingsRepository,
    internal val watchRepository: WatchRepository,
    internal val scheduler: PriceUpdateScheduler,
    internal val notifier: AppNotifier,
    internal val appearanceApplier: AppearanceApplier,
    internal val widgetUpdater: WidgetUpdater,
    private val coinLogoSync: com.cryptochecker.app.data.CoinLogoSync,
    internal val backupManager: com.cryptochecker.app.data.BackupManager,
    internal val appLockState: AppLockState,
    private val portfolioSnapshotUpdater: com.cryptochecker.app.widget.PortfolioSnapshotUpdater,
    internal val portfolioRepository: com.cryptochecker.app.data.portfolio.PortfolioRepository,
    /** «Alarm testen» und «Stimme testen». */
    private val testActions: SettingsTestActions,
    /** «Pünktlich prüfen» und «Letzte Prüfungen». */
    private val exactChecks: ExactCheckControl,
) : ViewModel() {

    /** «Alarm testen»: echter Alarm über den Weg der Kursalarme, ohne Nachtruhe. */
    suspend fun testAlarm(): com.cryptochecker.app.notification.AlarmTestResult = testActions.testAlarm()

    /** Ergebnis von Sichern/Wiederherstellen für eine kurze Meldung. */
    internal val _backupMessage = MutableStateFlow<BackupMessage?>(null)
    val backupMessage: StateFlow<BackupMessage?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() { _backupMessage.value = null }

    /**
     * Schutz der nächsten Sicherung (Passwort oder bewusst ohne) bis zur Dateiauswahl. Im
     * ViewModel, damit er ein Drehen übersteht; nach einem Prozess-Ende fehlt er — dann wird
     * nicht stillschweigend ohne Passwort gesichert.
     */
    internal var pendingExport: ExportRequest? = null

    internal class ExportRequest(val password: String?)

    /** Schritt beim Wiederherstellen: Passwort (nur verschlüsselte Sicherung), dann Bestätigen. */
    internal val _restoreStep = MutableStateFlow<RestoreStep?>(null)
    val restoreStep: StateFlow<RestoreStep?> = _restoreStep.asStateFlow()

    /**
     * Portfolio-Sperre gerade wirksam (Einstellung an und Sitzung gesperrt)? Sperrt das
     * Hintergrund-Limit wieder, während «Sichern» oder ein Wiederherstellen-Schritt offen ist,
     * schliessen sie sich — wie die Erfassen-Blätter. Erneut öffnen verlangt Entsperren.
     */
    val sessionLocked: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        settingsRepository.settings,
        appLockState.lockRequested,
    ) { settings, requested -> PortfolioLockPolicy.isLocked(settings.appLock, requested) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Nur die Oberfläche vor dem letzten Bestätigen fällt weg; ein bereits laufendes
        // Wiederherstellen (restoreBackup) hat den Schritt schon geleert und läuft zu Ende.
        viewModelScope.launch {
            // Gesperrt: auch die gelesene Sicherung (bis 64 MB samt Klartext) aus dem Speicher
            sessionLocked.collect { locked -> if (locked) { _restoreStep.value = null; backupManager.forgetPickedBackup() } }
        }
    }

    override fun onCleared() { backupManager.forgetPickedBackup() }

    // Start mit dem zuletzt gelesenen Stand statt Werks-Defaults: sonst springen Schalter beim Öffnen kurz um
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached)

    /** Paare der Merkliste für «Kursmitteilungen › Paare» (Auswahl je Paar). */
    val watches: StateFlow<List<com.cryptochecker.app.data.local.model.WatchEntity>> = watchRepository.observeWatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _languageTag = MutableStateFlow(AppLanguages.currentTag(context))

    /** Gewählte App-Sprache; leer = Systemsprache. */
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    /** Sprache wechseln; die Fenster bauen sich danach in der neuen Sprache auf. */
    fun setLanguage(tag: String) {
        _languageTag.value = tag
        AppLanguages.select(context, tag)
        viewModelScope.launch { widgetUpdater.updateAll() }
    }

    /** Hell/Dunkel für App und Widgets; null = wie das System. */
    fun setDarkMode(dark: Boolean?) = update {
        settingsRepository.setDarkMode(dark)
        appearanceApplier.applyNightMode(dark)
        widgetUpdater.updateAll()
    }

    /**
     * Akzentfarbe: Farbschema, Widgets und Benachrichtigungen sofort.
     * Das App-Icon folgt erst beim Verlassen der App (MainActivity.onStop) —
     * umgeschaltet im laufenden Betrieb schliessen manche Android-Versionen
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

    /** Eigene Grenze einer Bitcoin-Kennzahl; die Prüfung (zweimal täglich) gleich neu einplanen. */
    fun setIndicatorAlert(kind: com.cryptochecker.app.domain.market.IndicatorAlertKind, value: Int) = update {
        settingsRepository.setIndicatorAlert(kind, value)
        scheduler.apply(settingsRepository.current())
    }

    fun setGasAlertBtc(satPerVb: Int) = update { settingsRepository.setGasAlertBtc(satPerVb) }

    fun setDepegAlarm(enabled: Boolean) = update { settingsRepository.setDepegAlarm(enabled) }

    fun setDepegThreshold(tenths: Int) = update { settingsRepository.setDepegThreshold(tenths) }

    internal val _soundError = MutableStateFlow(false)
    val soundError: StateFlow<Boolean> = _soundError.asStateFlow()
    fun clearSoundError() { _soundError.value = false }

    fun setFearGreedBelow(value: Int) = update {
        settingsRepository.setFearGreedBelow(value)
        scheduler.apply(settingsRepository.current())
    }

    fun setFearGreedAbove(value: Int) = update {
        settingsRepository.setFearGreedAbove(value)
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
        // Portfolio-Logos (ab Werk an) brauchen jetzt vielleicht den Abgleich
        if (enabled) coinLogoSync.startIfEnabled()
        // Abgelegtes Portfolio-Widget: Werte bzw. «Portfolio ist aus»
        widgetUpdater.updatePortfolio(widgetUpdater.portfolioWidgetIds())
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

    /** Namen in der Merkliste; eingeschaltet lädt die App die Liste mit den Namen (ohne Bilder, falls Logos aus). */
    fun setWatchlistNames(show: Boolean) = update {
        settingsRepository.setWatchlistNames(show)
        if (show) coinLogoSync.startIfEnabled()
    }

    fun setPortfolioCoinLogos(show: Boolean) = update {
        settingsRepository.setPortfolioCoinLogos(show)
        coinLogoSync.startIfEnabled()
    }

    fun setCoinLogos(show: Boolean) = update {
        settingsRepository.setCoinLogos(show)
        coinLogoSync.startIfEnabled()
    }

    /** Widgets gleich neu zeichnen, damit Logos sofort erscheinen bzw. verschwinden. */
    fun setWidgetCoinLogos(show: Boolean) = update {
        settingsRepository.setWidgetCoinLogos(show)
        widgetUpdater.updateAll()
        coinLogoSync.startIfEnabled()
    }

    fun setWatchlistActivityCard(show: Boolean) = update { settingsRepository.setWatchlistActivityCard(show) }

    /**
     * «Basis der %-Änderung»: Bis neu gerechnet ist, zeigen Änderungen und Widgets «—» (der Stempel
     * passt nicht mehr); gleich eine Aktualisierung anstossen, Widgets und Portfolio-Widget folgen.
     */
    fun setChangeBasis(basis: com.cryptochecker.app.domain.watch.ChangeBasis) = update {
        if (settingsRepository.current().changeBasis == basis) return@update
        settingsRepository.setChangeBasis(basis)
        widgetUpdater.updateAll()
        scheduler.refreshNow()
    }

    /** «Zahl am App-Symbol» (gilt für neue Mitteilungen). */
    fun setAppIconBadge(show: Boolean) = update { settingsRepository.setAppIconBadge(show) }

    /** Umgerechnete Kurse in der Merkliste («≈ 61’234 CHF»). */
    fun setShowConverted(show: Boolean) = update { settingsRepository.setShowConverted(show) }

    /** Umrechnungswährung (gilt für Merkliste, Alarme und Portfolio). */
    fun setConversionCurrency(currency: String) = update {
        settingsRepository.setPortfolioCurrency(currency)
        // Portfolio-Widget gleich in der neuen Währung (sonst erst nach der nächsten Aktualisierung)
        portfolioSnapshotUpdater.refreshIfWidgets()
    }

    /**
     * «Wie aktuell?»: Hintergrund-Aktualisierung neu planen; `LivePriceStream` folgt der
     * Einstellung selbst.
     */
    fun setUpdateMode(mode: com.cryptochecker.app.settings.UpdateMode) = update {
        settingsRepository.setUpdateMode(mode)
        scheduler.apply(settingsRepository.current())
    }

    fun setBackgroundInterval(minutes: Int) = update {
        settingsRepository.setBackgroundInterval(minutes)
        scheduler.apply(settingsRepository.current())
    }

    // ── «Aktualisierung»: Pünktlich prüfen, Letzte Prüfungen ──

    /** «Letzte Prüfungen», neueste zuerst. */
    val checkRuns: StateFlow<List<com.cryptochecker.app.domain.refresh.CheckRun>> get() = exactChecks.runs

    /** Gibt es aktive Paare (sonst prüft der Hintergrund nichts: kein Wecker, kein Akku-Hinweis)? */
    val hasActiveWatches: StateFlow<Boolean> = watchRepository.observeWatches()
        .map { watches -> watches.any { !it.disabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setExactChecks(enabled: Boolean) = update {
        settingsRepository.setExactChecks(enabled)
        exactChecks.sync(enabled)
    }

    /** Nach der Rückkehr aus den Android-Einstellungen: Wecker stellen, falls jetzt erlaubt (oder absagen). */
    fun syncExactChecks() = update {
        exactChecks.sync(settingsRepository.current().exactChecks)
    }

    fun setIncludeRollingFutures(enabled: Boolean) = update {
        settingsRepository.setIncludeRollingFutures(enabled)
    }

    /** «inkl. Alpha-Tokens»: wie «Token & TradFi» nur für die Paar-Auswahl. */
    fun setIncludeAlphaTokens(enabled: Boolean) = update {
        settingsRepository.setIncludeAlphaTokens(enabled)
        // Eingeschaltet: Alpha-Liste holen, auch wenn Logos und Namen aus sind
        if (enabled) coinLogoSync.startIfEnabled()
    }

    /** «Token & TradFi»: wirkt auf die Paar-Auswahl, nie auf schon gemerkte Paare. */
    fun setAssetScope(scope: com.cryptochecker.app.settings.AssetScope) = update {
        if (settingsRepository.current().assetScope == scope) return@update
        settingsRepository.setAssetScope(scope)
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

    /** Preis-Overlay (nur Android); das Overlay folgt dem Schalter selbst (`PriceOverlay`). */
    fun setPriceOverlay(enabled: Boolean) = update {
        settingsRepository.setPriceOverlay(enabled)
    }

    fun setNotificationChangePercent(percent: Double) = update {
        settingsRepository.setNotificationChangePercent(percent)
    }

    fun setTtsEnabled(enabled: Boolean) = update {
        settingsRepository.setTtsEnabled(enabled)
        if (!enabled) testActions.stopSpeech()
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
    fun testSpeech() = update { testActions.testSpeech() }

    // ---------------- Nachtruhe ----------------

    fun setQuietHoursEnabled(enabled: Boolean) = update { settingsRepository.setQuietHoursEnabled(enabled) }

    fun setQuietHoursStart(minute: Int) = update { settingsRepository.setQuietHoursStart(minute) }

    fun setQuietHoursEnd(minute: Int) = update { settingsRepository.setQuietHoursEnd(minute) }

    // ---------------- Portfolio-Sperre ----------------

    /** «Beträge verbergen» (Portfolio und Portfolio-Widget). */
    fun setHidePortfolioAmounts(hidden: Boolean) = update {
        settingsRepository.setHidePortfolioAmounts(hidden)
        portfolioSnapshotUpdater.redrawWidgets()
    }

    /** Steuer-Vorlage der Arbeitsunterlage (Export «Stichtag, Arbeitsunterlage»). */
    fun setTaxTemplate(template: com.cryptochecker.app.domain.portfolio.TaxTemplate) = update {
        settingsRepository.setTaxTemplate(template)
    }

    /** Hinweis zu den Länder-Vorlagen (Beta) mit OK bestätigt. */
    fun confirmTaxTemplate() = update { settingsRepository.setTaxTemplateConfirmed() }

    /** Einstandsmethode FIFO | Ø (Portfolio, Widget «Gesamt» ab der nächsten Aufnahme, Steuer-Export). */
    fun setCostMethod(method: com.cryptochecker.app.domain.portfolio.CostMethod) = update {
        settingsRepository.setCostMethod(method)
    }

    internal fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
