package com.cryptochecker.app.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import kotlinx.coroutines.flow.map
import timber.log.Timber

// Ablage der Einstellungen ([SettingsRepository]): DataStore samt Ersatz bei beschädigter Datei und den einmaligen
// Migrationen beim ersten Lesen.

/**
 * Beschädigte Datei (z. B. halb geschrieben oder aus einer Systemsicherung wiederhergestellt):
 * durch leere Einstellungen ersetzen (= Standardwerte) statt bei jedem Start abzustürzen. Die Migrationen
 * sehen dann eine neue Installation (Portfolio aus); sind Portfolio-Daten da, schaltet [PortfolioDataCheck]
 * das Portfolio beim Start wieder ein. Nicht still: Intervall und Währung stehen auf dem Standard —
 * einmaliger Hinweis ([StorageRecoveryNotice.recordSettingsReset]); die Portfolio-Sperre gilt als an, bis
 * der Nutzer sie selbst setzt ([SettingsLockFallback]). DataStore ruft den Handler nur bei unlesbarem Inhalt auf, nie bei
 * fehlender Datei (neue Installation) oder einem Lesefehler (IOException, siehe [SettingsLockFallback]).
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Timber.w(e, "Einstellungen beschädigt – Standardwerte")
        settingsStoreContext?.let { context ->
            runCatching { StorageRecoveryNotice.recordSettingsReset(context) }
                .onFailure { Timber.w(it, "Hinweis «Einstellungen zurückgesetzt» nicht vorgemerkt") }
        }
        emptyPreferences()
    },
    // Reihenfolge: zuerst «Nicht wegwischbar», solange eine neue Installation noch leer ist; jede
    // spätere Migration zählt die von den vorherigen geschriebenen Schlüssel nicht mit
    produceMigrations = { context ->
        // Der Ersatz-Handler bekommt keinen Context: hier merken (läuft beim Anlegen des DataStore, vor jedem Lesen)
        settingsStoreContext = context.applicationContext
        listOf(OngoingNotificationsMigration, InstallDefaultsMigration, UpdateModeMigration)
    },
)

/** App-Context für den Ersatz-Handler von [settingsDataStore] (gesetzt in `produceMigrations`). */
// Nur der App-Context (applicationContext), kein Activity-Context: kein Speicherleck
@SuppressLint("StaticFieldLeak")
@Volatile private var settingsStoreContext: Context? = null

/**
 * «Nicht wegwischbar» einmalig festschreiben (Standard neu aus): Fehlt der Schlüssel,
 * erhält eine bestehende Installation (schon Einstellungen gespeichert) den bisherigen Standard an,
 * eine neue (noch nichts gespeichert) den neuen Standard aus ([AppSettings.ongoingForMigration]).
 * Läuft vor [UpdateModeMigration], die auch bei neuen Installationen sofort schreibt (und diesen
 * Schlüssel beim Erkennen einer neuen Installation nicht mitzählt).
 */
private object OngoingNotificationsMigration : DataMigration<Preferences> {
    private val key = booleanPreferencesKey(SettingsRepository.KEY_ONGOING_NOTIFICATIONS)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[key] == null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[key] = AppSettings.ongoingForMigration(existingInstall = currentData.asMap().isNotEmpty())
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}

/**
 * Geänderte Standards von Build 43 einmalig festschreiben («Coin-Namen» und «Umgerechnete Kurse» neu an,
 * «inkl. Alpha-Tokens» neu aus), ebenso Build 44 (Portfolio-Tab neu aus): Fehlt ein Schlüssel, erhält eine
 * bestehende Installation den bisherigen Standard, eine neue den neuen ([AppSettings.defaultForMigration]).
 * Eine Installation aus Build 43 hat die drei ersten Schlüssel schon und gilt damit als bestehende. Läuft nach
 * [OngoingNotificationsMigration] (deren Schlüssel nicht mitzählt) und vor [UpdateModeMigration]; so gilt
 * eine Installation aus Build 42 (mit «update_mode») sicher als bestehende.
 */
private object InstallDefaultsMigration : DataMigration<Preferences> {
    private val namesKey = booleanPreferencesKey(SettingsRepository.KEY_WATCHLIST_NAMES)
    private val convertedKey = booleanPreferencesKey(SettingsRepository.KEY_SHOW_CONVERTED)
    private val alphaKey = booleanPreferencesKey(SettingsRepository.KEY_INCLUDE_ALPHA_TOKENS)
    private val portfolioKey = booleanPreferencesKey(SettingsRepository.KEY_PORTFOLIO_ENABLED)
    private val portfolioCheckKey = booleanPreferencesKey(SettingsRepository.KEY_PORTFOLIO_DATA_CHECK)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[namesKey] == null || currentData[convertedKey] == null || currentData[alphaKey] == null ||
            currentData[portfolioKey] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        // Eigene Schlüssel zählen mit: Steht nur einer davon da, hat ihn der Nutzer selbst gesetzt
        val existing = AppSettings.isExistingInstall(
            currentData.asMap().keys.map { it.name },
            ignored = setOf(SettingsRepository.KEY_ONGOING_NOTIFICATIONS),
        )
        val current = AppSettings()
        return currentData.toMutablePreferences().apply {
            if (currentData[namesKey] == null) this[namesKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_WATCHLIST_NAMES, current.watchlistNames)
            if (currentData[convertedKey] == null) this[convertedKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_SHOW_CONVERTED, current.showConverted)
            if (currentData[alphaKey] == null) this[alphaKey] = AppSettings.defaultForMigration(
                existing, AppSettings.LEGACY_INCLUDE_ALPHA_TOKENS, current.includeAlphaTokens)
            if (currentData[portfolioKey] == null) {
                this[portfolioKey] = AppSettings.defaultForMigration(
                    existing, AppSettings.LEGACY_PORTFOLIO_ENABLED, current.portfolioEnabled)
                // Standard einer neuen Installation (auch nach beschädigter Datei): beim Start prüfen, ob doch
                // Portfolio-Daten da sind ([PortfolioDataCheck])
                if (!existing) this[portfolioCheckKey] = true
            }
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * «Wie aktuell?» beim ersten Lesen nach dem Update einmalig aus den früheren Schaltern übernehmen
 * und speichern ([UpdateMode.resolve]): «Häufig aktualisieren» oder «Live-Kurse» an → Live, solange
 * offen, sonst Sparsam. Neue Installation (noch nichts gespeichert) → Standard. Ein gespeichertes
 * «Immer kurz» (vor 1.0) wird einmalig als Live, solange offen gespeichert; der frühere Schalter
 * «Häufig aktualisieren» dabei auf aus, damit eine ältere Version nach einem Downgrade dasselbe liest.
 */
private object UpdateModeMigration : DataMigration<Preferences> {
    private val modeKey = stringPreferencesKey(SettingsRepository.KEY_UPDATE_MODE)
    private val liveServiceKey = booleanPreferencesKey(SettingsRepository.KEY_LIVE_SERVICE)
    private val webSocketKey = booleanPreferencesKey(SettingsRepository.KEY_LIVE_WEBSOCKET)
    private val backgroundKey = booleanPreferencesKey(SettingsRepository.KEY_BACKGROUND_UPDATES)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        UpdateMode.needsMigration(currentData[modeKey])

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mode = UpdateMode.resolve(
            stored = currentData[modeKey],
            legacyLiveService = currentData[liveServiceKey],
            legacyWebSocket = currentData[webSocketKey],
            legacyBackground = currentData[backgroundKey],
            // Die eben von [OngoingNotificationsMigration] und [InstallDefaultsMigration] geschriebenen
            // Schlüssel nicht mitzählen, sonst gälte jede neue Installation als bestehende (Sparsam statt Standard)
            existingInstall = AppSettings.isExistingInstall(
                currentData.asMap().keys.map { it.name },
                ignored = SettingsRepository.MIGRATION_WRITTEN_KEYS,
            ),
        )
        return currentData.toMutablePreferences().apply {
            this[modeKey] = mode.name
            if (currentData[liveServiceKey] == true) this[liveServiceKey] = false
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
