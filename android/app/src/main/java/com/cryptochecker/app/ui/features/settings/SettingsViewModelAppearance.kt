// Akzentfarbe und Basis der %-Änderung setzen — Teil von SettingsViewModel.
package com.cryptochecker.app.ui.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.lock.PortfolioLockPolicy
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.AccentColor
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.settings.PriceColorScheme
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.setup.AppLanguages
import com.cryptochecker.app.setup.AppearanceApplier
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.app.work.PriceUpdateScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Akzentfarbe: Farbschema, Widgets und Benachrichtigungen sofort.
 * Das App-Icon folgt erst beim Verlassen der App (MainActivity.onStop) —
 * umgeschaltet im laufenden Betrieb schliessen manche Android-Versionen
 * sonst die App.
 */
fun SettingsViewModel.setAccentColor(accent: AccentColor) = update {
    settingsRepository.setAccentColor(accent)
    // Frisch lesen: aktualisiert auch die zwischengespeicherte Farbe der Meldungen
    val settings = settingsRepository.current()
    widgetUpdater.updateAll()
    notifier.refreshColors(watchRepository.getWatches(), ongoing = settings.ongoingNotifications)
}

/**
 * «Basis der %-Änderung»: Bis neu gerechnet ist, zeigen Änderungen und Widgets «—» (der Stempel
 * passt nicht mehr); gleich eine Aktualisierung anstossen, Widgets und Portfolio-Widget folgen.
 */
fun SettingsViewModel.setChangeBasis(basis: com.cryptochecker.app.domain.watch.ChangeBasis) = update {
    if (settingsRepository.current().changeBasis == basis) return@update
    settingsRepository.setChangeBasis(basis)
    widgetUpdater.updateAll()
    scheduler.refreshNow()
}

/** Darstellung «Nach Uhrzeit» wählen ([com.cryptochecker.app.domain.settings.ThemeSchedule]). */
fun SettingsViewModel.setThemeScheduled() = update {
    settingsRepository.setThemeScheduled(true)
    applyAppearance()
}

/** «Dunkel ab» (Minuten seit Mitternacht). */
fun SettingsViewModel.setThemeDarkFrom(minute: Int) = update {
    settingsRepository.setThemeDarkFrom(minute)
    applyAppearance()
}

/** «Hell ab» (Minuten seit Mitternacht). */
fun SettingsViewModel.setThemeLightFrom(minute: Int) = update {
    settingsRepository.setThemeLightFrom(minute)
    applyAppearance()
}

/** Hell/Dunkel neu anwenden: Fenster und Systemdialoge, Widgets mit «System» (folgen der App). */
internal suspend fun SettingsViewModel.applyAppearance() {
    appearanceApplier.applyNightMode(settingsRepository.current())
    widgetUpdater.updateAll()
}
