package com.cryptochecker.app.ui.features.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * «Auf der Uhr zeigen» (nur Android, `WatchNotifyBridge`); beim Einschalten bittet die Uhr-App beim nächsten Öffnen um
 * die Erlaubnis für Mitteilungen. Eigene Datei wegen der Zeilengrenze von [SettingsViewModel].
 */
internal fun SettingsViewModel.setWatchNotifications(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepository.setWatchNotifications(enabled)
        if (enabled) notifier.watchBridge.hello()
    }
}
