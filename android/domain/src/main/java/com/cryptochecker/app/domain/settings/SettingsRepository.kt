package com.cryptochecker.app.domain.settings

import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryView
import kotlinx.coroutines.flow.Flow

/**
 * Einstellungen lesen und schreiben — Schnittstelle für Oberfläche und Hintergrund; die Ablage (DataStore, Grenzen,
 * Nebenwirkungen) liegt in :data (`DataStoreSettingsRepository`). Die gesicherten Setter stehen in [SettingsBackupTarget].
 */
interface SettingsRepository : SettingsBackupTarget {
    val settings: Flow<AppSettings>

    // Ausschnitte von settings ([SettingsSlices]): geben nur neu aus, wenn sich ihr Teil ändert
    val watchlistSettings: Flow<WatchlistSettings>
    val alarmSettings: Flow<AlarmSettings>
    val portfolioSettings: Flow<PortfolioSettings>
    val updateSettings: Flow<UpdateSettings>

    /** Zuletzt gelesene Einstellungen, ohne zu warten; vor dem ersten Lesen die Standardwerte. */
    val cached: AppSettings

    /** true, sobald [cached] einmal aus der Datei gelesen wurde. */
    val loaded: Boolean

    suspend fun setExactChecks(enabled: Boolean)
    suspend fun setPriceOverlay(enabled: Boolean)
    suspend fun setWatchNotifications(enabled: Boolean)
    suspend fun setShowHttpLog(show: Boolean)
    suspend fun setDeveloperUnlocked(unlocked: Boolean)
    suspend fun setGestureHintSeen(seen: Boolean)
    suspend fun setFirstAlarmCardDismissed(dismissed: Boolean)

    /** Öffnen am lokalen Epoch-Tag [today] zählen; derselbe Tag schreibt nichts Neues. */
    suspend fun recordUsageDay(today: Long)

    /** Alarmton setzen (null = Standardton); gibt die neue Kanal-Version zurück. */
    suspend fun setAlarmSound(uri: String?, name: String?): Int
    suspend fun setAboutSeen(seen: Boolean)
    suspend fun setBatteryPromptSeen(seen: Boolean)

    /** Spalten-Sortierung aus dem gespeicherten Stand weiterschalten, in einem Schreibvorgang. */
    suspend fun updateWatchlistColumnSort(next: (String?) -> String?)

    /** null = «Alle». */
    suspend fun setWatchlistGroup(group: String?)
    suspend fun setFirstAlarmShown(shown: Boolean)
    suspend fun setAlarmTimingHintShown(shown: Boolean)
    suspend fun setFirstPairAdded(added: Boolean)
    suspend fun setSheetChartLine(line: Boolean)
    suspend fun setPortfolioHistoryExpanded(expanded: Boolean)
    suspend fun setPortfolioHistoryRange(range: PortfolioHistoryRange)
    suspend fun setPortfolioHistoryView(view: PortfolioHistoryView)
    suspend fun setPortfolioHistoryCandles(candles: Boolean)
    suspend fun setPortfolioHistoryPnl(pnl: Boolean)

    /** Eigener Bitcoin-Server für Adressabfragen; null = öffentlich. */
    val addressServerFlow: Flow<String?>

    /** Server unbekannt (Einstellungen ersetzt): Bitcoin-Adressen nicht abfragen. */
    val addressServerUnknownFlow: Flow<Boolean>
    suspend fun addressServerUnknown(): Boolean

    /** Eigener Server eingetragen oder öffentliche Knoten bestätigt. */
    suspend fun confirmAddressServer()
    suspend fun setTaxTemplateConfirmed()
    suspend fun setMarketMoreExpanded(expanded: Boolean)

    /**
     * Schreibweg der Oberfläche ausserhalb von «Einstellungen»: ein Schreibfehler (z. B. Speicher voll) beendet die App
     * nicht, sondern wird genannt; true = geschrieben.
     */
    suspend fun guarded(block: suspend SettingsRepository.() -> Unit): Boolean
}
