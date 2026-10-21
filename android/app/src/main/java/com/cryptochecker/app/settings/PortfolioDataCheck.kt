package com.cryptochecker.app.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Einstellungen als neue Installation angelegt (fehlend oder beschädigt und durch leere ersetzt): Die Migration
 * schreibt «Portfolio aus» und den Merker [SettingsRepository.KEY_PORTFOLIO_DATA_CHECK]. Beim Start prüft
 * [resolve], ob trotzdem Portfolio-Daten da sind, und schaltet das Portfolio dann wieder ein
 * ([AppSettings.enablePortfolioForData]). Seit Build 94.
 */
object PortfolioDataCheck {

    private val checkKey = booleanPreferencesKey(SettingsRepository.KEY_PORTFOLIO_DATA_CHECK)

    /**
     * Merker gesetzt: [hasPortfolioData] fragen, Portfolio bei Bedarf einschalten, Merker entfernen. Portfolio
     * nicht lesbar (Fehler) oder nur leer im Arbeitsspeicher ([portfolioInMemory], [keepMarker]): Merker bleibt, der
     * nächste Start versucht es erneut.
     */
    suspend fun resolve(
        context: Context,
        portfolioInMemory: () -> Boolean = { false },
        hasPortfolioData: suspend () -> Boolean,
    ) {
        val store = context.settingsDataStore
        // Einstellungen nicht lesbar (I/O): nichts tun, der Merker bleibt
        if (runCatchingCancellable { store.data.first()[checkKey] }.getOrNull() != true) return
        val hasData = runCatchingCancellable { hasPortfolioData() }.getOrElse {
            Timber.w(it, "Portfolio-Daten nicht prüfbar – später erneut")
            return
        }
        // Erst nach dem Lesen fragen: die Datenbank ist dann geöffnet und weiss, ob sie nur im Arbeitsspeicher liegt
        if (keepMarker(hasData, portfolioInMemory())) {
            Timber.w("Portfolio nur im Arbeitsspeicher (Schlüssel nicht lesbar) – Prüfung später erneut")
            return
        }
        runCatchingCancellable {
            store.edit { prefs -> applyCheck(prefs, hasData) }
        }.onFailure { Timber.w(it, "Portfolio-Prüfung nicht gespeichert") }
    }

    /**
     * Merker behalten statt «keine Daten» zu entscheiden? Ja, wenn das Portfolio leer ist, aber nur im Arbeitsspeicher
     * liegt (Schlüssel gerade nicht verfügbar, [com.cryptochecker.app.data.portfolio.PortfolioDatabase.inMemory]): Der
     * gespeicherte Stand ist dann nicht zu sehen — der Merker darf nicht weg, sonst bliebe das Portfolio nach dem
     * nächsten Start mit lesbarem Schlüssel aus (seit Build 95). Daten gefunden: entscheiden (einschalten).
     */
    internal fun keepMarker(hasData: Boolean, portfolioInMemory: Boolean): Boolean = !hasData && portfolioInMemory

    private fun applyCheck(prefs: MutablePreferences, hasData: Boolean) {
        val enable = AppSettings.enablePortfolioForData(
            resetDefaults = prefs[checkKey] == true,
            portfolioEnabled = prefs[SettingsKeys.portfolioEnabled] == true,
            hasPortfolioData = hasData,
        )
        if (enable) {
            prefs[SettingsKeys.portfolioEnabled] = true
            Timber.i("Einstellungen neu angelegt, Portfolio-Daten vorhanden – Portfolio wieder an")
        }
        prefs.remove(checkKey)
    }
}
