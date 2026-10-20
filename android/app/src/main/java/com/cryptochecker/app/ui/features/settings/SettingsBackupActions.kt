// Sichern und Wiederherstellen in den Einstellungen (Teil von `SettingsViewModel`): Export vorbereiten und
// schreiben, Datei wählen, Passwort prüfen, Rückfrage, Wiederherstellen – samt den Zuständen `RestoreStep`
// und `BackupMessage`.
// Eigene Datei, weil der Ablauf mehrere Schritte mit eigenem Zustand hat; Erweiterungsfunktionen auf dem
// ViewModel, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.settings

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.lock.PortfolioLockPolicy
import kotlinx.coroutines.launch

/** Vor dem Öffnen der Dateiauswahl: [password] null = ohne Passwortschutz. */
fun SettingsViewModel.prepareExport(password: String?) {
    pendingExport = SettingsViewModel.ExportRequest(password)
}

/** Dateiauswahl abgebrochen. */
fun SettingsViewModel.cancelExport() {
    pendingExport = null
}

fun SettingsViewModel.exportBackup(uri: android.net.Uri) = update {
    val request = pendingExport
    pendingExport = null
    _backupMessage.value = if (request == null) {
        BackupMessage.Failed
    } else {
        runCatchingCancellable { backupManager.export(uri, request.password) }
            .fold({ BackupMessage.Exported }, { BackupMessage.failure(it) })
    }
}

/** Sperr-Zustand frisch gelesen (die Einstellungen können noch nicht geladen sein). */
internal suspend fun SettingsViewModel.lockedNow(): Boolean =
    PortfolioLockPolicy.isLocked(settingsRepository.current().appLock, appLockState.lockRequested.value)

/** Datei gewählt: verschlüsselt → Passwort, sonst gleich die Rückfrage. */
fun SettingsViewModel.restorePicked(uri: android.net.Uri) = update {
    // Unterwegs (Dateiauswahl über dem Hintergrund-Limit) wieder gesperrt: verwerfen
    if (lockedNow()) {
        _restoreStep.value = null
        return@update
    }
    _restoreStep.value = try {
        if (backupManager.needsPassword(uri)) RestoreStep.Password(uri)
        else RestoreStep.Confirm(uri, null, backupManager.invalidEntries(uri, null))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        _backupMessage.value = BackupMessage.failure(e)
        null
    }
}

/** Passwort prüfen; falsch → nochmals fragen (mit Hinweis), richtig → Rückfrage. */
fun SettingsViewModel.submitRestorePassword(uri: android.net.Uri, password: String) = update {
    // Fehlversuche zählen: jeder leert das Feld neu (nicht nur der erste); der Hinweis bleibt während der Prüfung stehen
    val failed = (_restoreStep.value as? RestoreStep.Password)?.failedAttempts ?: 0
    _restoreStep.value = RestoreStep.Password(uri, wrong = failed > 0, checking = true, failedAttempts = failed)
    val next: RestoreStep? = try {
        // Prüft das Passwort und zählt zugleich ungültige Portfolio-Einträge (nur eine Schlüsselableitung)
        RestoreStep.Confirm(uri, password, backupManager.invalidEntries(uri, password))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: com.cryptochecker.app.data.BackupCrypto.WrongPasswordException) {
        RestoreStep.Password(uri, wrong = true, failedAttempts = failed + 1)
    } catch (e: Exception) {
        _backupMessage.value = BackupMessage.failure(e)
        null
    }
    // Während der Prüfung wieder gesperrt: keine Rückfrage mehr zeigen
    _restoreStep.value = if (lockedNow()) null else next
}

fun SettingsViewModel.cancelRestore() {
    _restoreStep.value = null
}

fun SettingsViewModel.restoreBackup(uri: android.net.Uri, password: String?, acceptInvalid: Int = 0) = update {
    _restoreStep.value = null
    // Wer hier wiederherstellt, ist schon drin: Schaltet die Sicherung die Portfolio-Sperre
    // ein, gilt sie erst ab dem nächsten Kaltstart bzw. nach dem Hintergrund-Limit —
    // nicht sofort in dieser Sitzung (vor dem Schreiben, damit nichts aufblitzt).
    // Nur wenn die Sperre bisher aus war: sonst höbe ein Wiederherstellen nach über
    // einer Minute in der Dateiauswahl die gerade fällige Sperre ohne Entsperren auf.
    if (!settingsRepository.current().appLock) appLockState.unlock()
    _backupMessage.value = runCatchingCancellable { backupManager.restore(uri, password, acceptInvalid) }
        .fold(
            onSuccess = { result ->
                // Meldungen der ersetzten Paare und Alarme entfernen: sie zeigten alte Daten, und
                // gleiche Ids neuer Paare bzw. Alarme würden sie sonst stillschweigend «übernehmen»
                runCatchingCancellable {
                    notifier.cancelAllPrices(result.replacedWatchIds)
                    result.replacedWatchIds.forEach { notifier.cancelActivity(it) }
                    notifier.cancelAlarms(result.replacedAlarmIds, result.replacedPortfolioAlarmIds)
                }.onFailure { timber.log.Timber.w(it, "Meldungen nach dem Wiederherstellen nicht entfernt") }
                // Neue Einstellungen gleich anwenden
                val s = settingsRepository.current()
                scheduler.apply(s)
                appearanceApplier.applyNightMode(s.darkMode)
                widgetUpdater.updateAll()
                // Gleich Kurse holen: Widgets, Kurs-Mitteilungen und Alarme der neuen Paare
                // warteten sonst bis zur nächsten Hintergrund-Aktualisierung (bis 15 Min. und mehr)
                if (result.watches > 0) scheduler.refreshNow()
                BackupMessage.Restored(result.watches, result.alarms, result.appLockKept)
            },
            onFailure = { BackupMessage.failure(it) }
        )
}

/**
 * Sichern: Enthält die Datei Portfolio-Daten und ist gesperrt, erst entsperren.
 * [launch] erhält, ob Portfolio-Daten dabei sind (dann ist der Passwortschutz vorgewählt).
 */
fun SettingsViewModel.startBackupExport(activity: androidx.fragment.app.FragmentActivity?, reason: String, launch: (Boolean) -> Unit) {
    viewModelScope.launch {
        val hasPortfolio = try {
            // Beobachtete Adressen sind auch Portfolio-Daten
            PortfolioLockPolicy.backupHasPortfolioData(
                transactions = portfolioRepository.getTransactions().size,
                watchedAddresses = portfolioRepository.getWatchedAddresses().size,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        gate(activity, reason, { locked -> PortfolioLockPolicy.backupExportNeedsUnlock(locked, hasPortfolio) }) {
            launch(hasPortfolio)
        }
    }
}

/** Wiederherstellen: solange gesperrt, erst entsperren (die Sicherung kann die Sperre ausschalten). */
fun SettingsViewModel.startRestore(activity: androidx.fragment.app.FragmentActivity?, reason: String, launch: () -> Unit) =
    gate(activity, reason, PortfolioLockPolicy::restoreNeedsUnlock, launch)

/** Wiederherstellen in Schritten (siehe [SettingsViewModel.restoreStep]). */
sealed interface RestoreStep {
    val uri: android.net.Uri

    /**
     * Passwort der verschlüsselten Sicherung; [wrong] nach einem Fehlversuch, [checking] während der Prüfung,
     * [failedAttempts] Zahl der Fehlversuche (leert das Feld bei jedem neuen).
     */
    data class Password(
        override val uri: android.net.Uri,
        val wrong: Boolean = false,
        val checking: Boolean = false,
        val failedAttempts: Int = 0,
    ) : RestoreStep

    /**
     * Rückfrage «Ersetzen?»; [password] null bei einer lesbaren Sicherung; [invalid] = ungültige
     * Portfolio-Einträge, die nicht übernommen würden (die Rückfrage nennt sie). Kein data class: kein
     * Passwort in toString.
     */
    class Confirm(override val uri: android.net.Uri, val password: String?, val invalid: Int = 0) : RestoreStep {
        /** Sicherung ohne Passwort: keine Echtheitsprüfung — die Rückfrage warnt davor. */
        val unencrypted: Boolean get() = password == null
    }
}

sealed interface BackupMessage {
    data object Exported : BackupMessage
    /** [appLockKept] = Sicherung ohne Passwort wollte die Portfolio-Sperre ausschalten; sie blieb an. */
    data class Restored(val watches: Int, val alarms: Int, val appLockKept: Boolean = false) : BackupMessage
    data object Failed : BackupMessage
    /** Datei über 16 MB oder zu tief verschachtelt ([com.cryptochecker.app.data.BackupLimits]). */
    data object TooLarge : BackupMessage
    /** Sicherung einer neueren App-Version ([com.cryptochecker.app.data.BackupVersion]): nichts übernommen. */
    data object NewerVersion : BackupMessage
    /** Geschriebene Datei liest sich nicht gleich zurück ([com.cryptochecker.app.data.BackupNotVerifiedException]): neue Datei wählen. */
    data object NotVerified : BackupMessage
    /** Zu wenig Arbeitsspeicher für Argon2id ([com.cryptochecker.app.data.BackupCrypto.NotEnoughMemoryException]). */
    data object NotEnoughMemory : BackupMessage
    /** Gespeicherter Stand gerade nicht lesbar ([com.cryptochecker.app.data.BackupStorageKeptException]): nach Neustart. */
    data object StorageKept : BackupMessage

    companion object {
        fun failure(e: Throwable): BackupMessage = when (e) {
            is com.cryptochecker.app.data.BackupLimits.TooLargeException -> TooLarge
            is com.cryptochecker.app.data.BackupCrypto.NotEnoughMemoryException -> NotEnoughMemory
            is com.cryptochecker.app.data.BackupVersion.NewerVersionException -> NewerVersion
            is com.cryptochecker.app.data.BackupNotVerifiedException -> NotVerified
            is com.cryptochecker.app.data.BackupStorageKeptException -> StorageKept
            else -> Failed
        }
    }
}
