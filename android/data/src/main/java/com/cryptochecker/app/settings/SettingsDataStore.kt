package com.cryptochecker.app.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import timber.log.Timber

// Ablage der Einstellungen ([SettingsRepository]): DataStore samt Ersatz bei beschädigter Datei und dem Merker
// «Portfolio-Daten prüfen» für neu angelegte Einstellungen.

/**
 * Beschädigte Datei (z. B. halb geschrieben oder aus einer Systemsicherung wiederhergestellt):
 * durch leere Einstellungen ersetzen (= Standardwerte) statt bei jedem Start abzustürzen. [NewSettingsMarker]
 * sieht dann neu angelegte Einstellungen (Portfolio aus); sind Portfolio-Daten da, schaltet [PortfolioDataCheck]
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
    produceMigrations = { context ->
        // Der Ersatz-Handler bekommt keinen Context: hier merken (läuft beim Anlegen des DataStore, vor jedem Lesen)
        settingsStoreContext = context.applicationContext
        listOf(NewSettingsMarker)
    },
)

/** App-Context für den Ersatz-Handler von [settingsDataStore] (gesetzt in `produceMigrations`). */
// Nur der App-Context (applicationContext), kein Activity-Context: kein Speicherleck
@SuppressLint("StaticFieldLeak")
@Volatile private var settingsStoreContext: Context? = null

/**
 * Neu angelegte Einstellungen (noch nichts gespeichert: neue Installation, fehlende oder durch leere ersetzte Datei):
 * den Merker [SettingsRepository.KEY_PORTFOLIO_DATA_CHECK] setzen. Beim Start prüft dann [PortfolioDataCheck], ob
 * trotzdem Portfolio-Daten da sind.
 */
private object NewSettingsMarker : DataMigration<Preferences> {
    private val checkKey = booleanPreferencesKey(SettingsRepository.KEY_PORTFOLIO_DATA_CHECK)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData.asMap().isEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { this[checkKey] = true }.toPreferences()

    override suspend fun cleanUp() = Unit
}
