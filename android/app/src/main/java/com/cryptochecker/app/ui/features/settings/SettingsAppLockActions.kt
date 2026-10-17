// App-Sperre in den Einstellungen (Teil von `SettingsViewModel`): ein-/ausschalten, «Portfolio in
// Systemsicherung» und das Entsperren vor geschützten Aktionen (`gate`).
// Eigene Datei, weil die Sperr-Regeln (PortfolioLockPolicy) zusammen lesbar sein sollen.
package com.cryptochecker.app.ui.features.settings

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.PortfolioLockPolicy
import kotlinx.coroutines.launch

/**
 * «Portfolio in Systemsicherung»; die Kopie folgt sofort (PortfolioBackupMirror). Einschalten verlangt
 * Entsperren, solange das Portfolio gesperrt ist (PortfolioLockPolicy.systemBackupNeedsUnlock).
 */
fun SettingsViewModel.setPortfolioSystemBackup(activity: androidx.fragment.app.FragmentActivity?, reason: String, allowed: Boolean) =
    gate(activity, reason, { locked -> PortfolioLockPolicy.systemBackupNeedsUnlock(locked, allowed) }) {
        update { settingsRepository.setPortfolioSystemBackup(allowed) }
    }

/** Ausschalten (nach [disableAppLock]); das Portfolio-Widget zeigt danach wieder Werte. */
fun SettingsViewModel.setAppLock(enabled: Boolean) = update {
    settingsRepository.setAppLock(enabled)
    widgetUpdater.updateAll()
}

/**
 * Einschalten erst nach einer erfolgreichen Entsperrung; sonst bleibt die Sperre aus.
 * Die gerade laufende Sitzung gilt danach als entsperrt.
 */
fun SettingsViewModel.enableAppLock(activity: androidx.fragment.app.FragmentActivity, reason: String) {
    if (appLockState.authenticating) return
    appLockState.authenticating = true
    AppLockAuth.authenticate(activity, reason) { ok ->
        appLockState.authenticating = false
        if (ok) {
            appLockState.unlock()
            setAppLock(true)
        }
    }
}

/** Ausschalten verlangt Entsperren, solange das Portfolio gesperrt ist (sonst wäre die Sperre umgangen). */
fun SettingsViewModel.disableAppLock(activity: androidx.fragment.app.FragmentActivity?, reason: String) =
    gate(activity, reason, PortfolioLockPolicy::disableNeedsUnlock) { setAppLock(false) }

/** [onUnlocked] sofort, wenn [needsUnlock] für den aktuellen Sperr-Zustand false ist; sonst nach der Abfrage. */
internal fun SettingsViewModel.gate(
    activity: androidx.fragment.app.FragmentActivity?,
    reason: String,
    needsUnlock: (Boolean) -> Boolean,
    onUnlocked: () -> Unit,
) {
    viewModelScope.launch {
        val locked = PortfolioLockPolicy.isLocked(settingsRepository.current().appLock, appLockState.lockRequested.value)
        appLockState.requireUnlock(activity, needsUnlock(locked), reason, onUnlocked)
    }
}
