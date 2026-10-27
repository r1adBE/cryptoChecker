// Portfolio-Sperre bei unlesbaren Einstellungen (Teil von `SettingsRepository`): unbekannt heisst gesperrt.
package com.cryptochecker.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import java.io.IOException

/**
 * Die Portfolio-Sperre ist kein «fail-open» mehr. Früher stand sie nach einer beschädigten
 * Einstellungsdatei (ersetzt durch Standardwerte, Sperre aus) oder einem Lesefehler still auf «aus» — wer das Gerät in
 * der Hand hatte, sah das Portfolio. Jetzt gilt der Wert als unbekannt und damit als «an» ([effective]):
 *  - Datei beschädigt: vorgemerkt in [StorageRecoveryNotice.recordSettingsReset] (eigene Ablage, nicht die kaputte
 *    Datei), bis der Nutzer die Sperre selbst setzt ([SettingsRepository.setAppLock] → [StorageRecoveryNotice.confirmAppLock]).
 *    Ohne Displaysperre bietet der Sperr-Zustand wie bisher an, sie nach Rückfrage auszuschalten.
 *  - Lesefehler (I/O): für diesen Lesevorgang Standardwerte mit Sperre an ([unreadable]); die Datei selbst ist
 *    unverändert, der nächste lesbare Stand gilt wieder. Hinweis «Einstellungen zurückgesetzt» wie bei beschädigter Datei.
 * Gespiegelt in iOS `SharedStorage.LoadedSettings.appLockAssumed` und `AppData.settingsWereReset`.
 */
object SettingsLockFallback {

    /** Rein (testbar): Sperre unbekannt → an; sonst die gespeicherte Einstellung. */
    fun effective(settings: AppSettings, lockUnknown: Boolean): AppSettings =
        if (lockUnknown && !settings.appLock) settings.copy(appLock = true) else settings

    /** Ablage beim Lesefehler: Standardwerte, aber Sperre an. */
    fun unreadable(): Preferences = mutablePreferencesOf(SettingsKeys.appLock to true)

    /** Einstellungen aus [data] samt Lesefehler und vorgemerkter unbekannter Sperre. */
    fun settings(context: Context, data: Flow<Preferences>): Flow<AppSettings> = combine(
        // Lesefehler (I/O): mit Standardwerten weiter statt Absturz; andere Fehler weiterreichen
        data.catch { e ->
            if (e !is IOException) throw e
            Timber.w(e, "Einstellungen nicht lesbar – Standardwerte, Portfolio-Sperre an")
            runCatchingCancellable { StorageRecoveryNotice.recordSettingsReset(context, lockUnknown = false) }
                .onFailure { Timber.w(it, "Hinweis «Einstellungen zurückgesetzt» nicht vorgemerkt") }
            emit(unreadable())
        },
        StorageRecoveryNotice.appLockUnknown(context),
    ) { prefs, lockUnknown -> effective(appSettingsOf(prefs), lockUnknown) }
}
