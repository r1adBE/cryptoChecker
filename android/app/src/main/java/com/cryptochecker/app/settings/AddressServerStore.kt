// Eigener Bitcoin-Server für Adressabfragen (Teil von `SettingsRepository`): eigener Schlüssel im Einstellungs-DataStore,
// ausserhalb von `AppSettings` (die Datei ist an der Zeilengrenze). In der Sicherung als «addressServerBtc».
package com.cryptochecker.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cryptochecker.app.domain.portfolio.AddressServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal object AddressServerStore {
    /** Normalisierte Basis-URL ([AddressServer.check]); fehlt = öffentliche Knoten. */
    val key = stringPreferencesKey("address_server_btc")

    /**
     * Gespeicherter Server (beim Lesen erneut geprüft; ungültig gilt als nicht gesetzt). Lesefehler werden nicht
     * abgefangen: Wer Adressen abfragt, fragt dann gar nicht statt still bei öffentlichen Knoten.
     */
    fun flow(store: DataStore<Preferences>): Flow<String?> =
        store.data.map { prefs -> prefs[key]?.let { AddressServer.check(it).url } }.distinctUntilChanged()

    /** Speichern (geprüft und normalisiert); null, leer oder ungültig entfernt den Eintrag. */
    suspend fun set(store: DataStore<Preferences>, url: String?) {
        val value = url?.let { AddressServer.check(it).url }
        store.edit { if (value == null) it.remove(key) else it[key] = value }
    }
}
