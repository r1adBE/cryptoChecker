package com.cryptochecker.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Was die Navigation aus den Einstellungen braucht: ist der Portfolio-Tab an? */
@HiltViewModel
class AppNavViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    // Startwert aus dem Zwischenspeicher (beim App-Start schon gelesen) — so springt die Leiste nicht.
    val portfolioEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.portfolioEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.portfolioEnabled)
}
