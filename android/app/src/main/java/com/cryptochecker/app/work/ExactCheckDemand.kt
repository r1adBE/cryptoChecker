package com.cryptochecker.app.work

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bedarf des Weckers «Pünktlich prüfen» ([BackgroundWorkPolicy.exactNeeded]): aktive Paare, ein scharfer Portfolio-Alarm
 * oder ein Gas-/Depeg-Alarm — dieselben Alarme, für die auch die periodische Aktualisierung läuft
 * ([BackgroundWorkPolicy.periodicNeeded]). Eine Quelle für den Wecker ([ExactCheckReceiver], [PriceUpdateWorker]), den
 * Schalter und den Text «nichts zu prüfen» in den Einstellungen — damit der Wecker nicht abgesagt wird, solange solche
 * Alarme laufen.
 */
@Singleton
class ExactCheckDemand @Inject constructor(
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val portfolioAlarmDao: PortfolioAlarmDao,
) {
    /** Bedarf jetzt (frisch gelesen). Fehler: lieber ja — sonst nähme der Abgleich den Wecker weg. */
    suspend fun hasWork(): Boolean = runCatchingCancellable {
        val settings = settingsRepository.current()
        need(
            settings,
            activePairs = watchRepository.getWatches().any { !it.disabled },
            // Portfolio aus: Datenbank gar nicht erst öffnen
            portfolioAlarmOn = settings.portfolioEnabled && portfolioAlarmDao.countEnabled() > 0,
        )
    }.getOrDefault(true)

    /** Bedarf laufend (Einstellungen): ändert sich mit Paaren, Portfolio-Alarmen und den Gas-/Depeg-Schaltern. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Boolean> {
        val portfolioAlarms = settingsRepository.settings
            .map { it.portfolioEnabled }
            .distinctUntilChanged()
            .flatMapLatest { on -> if (on) portfolioAlarmDao.observeAll().map { list -> list.any { it.enabled } } else flowOf(false) }
        return combine(
            settingsRepository.settings,
            watchRepository.observeWatches().map { watches -> watches.any { !it.disabled } }.distinctUntilChanged(),
            portfolioAlarms,
        ) { settings, activePairs, portfolioAlarmOn -> need(settings, activePairs, portfolioAlarmOn) }
            .distinctUntilChanged()
    }

    private fun need(settings: AppSettings, activePairs: Boolean, portfolioAlarmOn: Boolean): Boolean =
        BackgroundWorkPolicy.exactNeeded(
            activePairs = activePairs,
            portfolioAlarm = BackgroundWorkPolicy.portfolioWork(
                portfolioEnabled = settings.portfolioEnabled,
                alarmOn = portfolioAlarmOn,
                portfolioKept = DatabaseEncryptionNotice.isStoredKept(PortfolioDatabase.DB_NAME),
                // Ein Widget allein ist nichts zu «prüfen»: dafür reicht der Takt von WorkManager
                widget = false,
            ),
            marketAlarms = BackgroundWorkPolicy.marketAlarms(settings.gasAlertEthTenths, settings.gasAlertBtc, settings.depegAlarm),
            // Merkliste nur leer im Arbeitsspeicher (Schlüssel gerade nicht lesbar): unbekannt, also lieber ja
            watchlistKept = DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME),
        )
}
