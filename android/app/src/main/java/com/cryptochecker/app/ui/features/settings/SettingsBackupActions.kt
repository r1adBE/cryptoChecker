// Sichern und Wiederherstellen in den Einstellungen (Teil von `SettingsViewModel`): Export vorbereiten und
// schreiben, Datei wählen, Passwort prüfen, Rückfrage, Wiederherstellen – samt den Zuständen `RestoreStep`
// und `BackupMessage`.
// Eigene Datei, weil der Ablauf mehrere Schritte mit eigenem Zustand hat; Erweiterungsfunktionen auf dem
// ViewModel, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.ui.features.settings

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.ui.components.HapticEvent
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
        else RestoreStep.Confirm(uri, null, backupManager.preview(uri, null))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        _backupMessage.value = BackupMessage.failure(e)
        null
    } catch (e: OutOfMemoryError) {
        // Sicherung bis 64 MB: lieber eine Meldung als ein Absturz (seit Build 93)
        _backupMessage.value = BackupMessage.failure(e)
        null
    }
    if (_restoreStep.value == null) backupManager.forgetPickedBackup()
}

/** Passwort prüfen; falsch → nochmals fragen (mit Hinweis), richtig → Rückfrage. */
fun SettingsViewModel.submitRestorePassword(uri: android.net.Uri, password: String) = update {
    // Fehlversuche zählen: jeder leert das Feld neu (nicht nur der erste); der Hinweis bleibt während der Prüfung stehen
    val failed = (_restoreStep.value as? RestoreStep.Password)?.failedAttempts ?: 0
    _restoreStep.value = RestoreStep.Password(uri, wrong = failed > 0, checking = true, failedAttempts = failed)
    val next: RestoreStep? = try {
        // Prüft das Passwort und zählt zugleich ungültige Einträge und solche über den Grenzen (nur eine Schlüsselableitung)
        RestoreStep.Confirm(uri, password, backupManager.preview(uri, password))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: com.cryptochecker.app.data.BackupCrypto.WrongPasswordException) {
        RestoreStep.Password(uri, wrong = true, failedAttempts = failed + 1)
    } catch (e: Exception) {
        _backupMessage.value = BackupMessage.failure(e)
        null
    } catch (e: OutOfMemoryError) {
        _backupMessage.value = BackupMessage.failure(e)
        null
    }
    // Während der Prüfung wieder gesperrt: keine Rückfrage mehr zeigen
    _restoreStep.value = if (lockedNow()) null else next
    if (_restoreStep.value == null) backupManager.forgetPickedBackup()
}

fun SettingsViewModel.cancelRestore() {
    _restoreStep.value = null
    // Gemerkte Datei samt Klartext freigeben (bis 64 MB)
    backupManager.forgetPickedBackup()
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
                BackupMessage.Restored(result.watches, result.alarms, result.appLockKept, result.droppedPairs, result.droppedAlarms)
            },
            onFailure = { BackupMessage.failure(it) }
        )
    // Auch wenn das Wiederherstellen vor dem Lesen abbrach: nichts mehr gemerkt
    backupManager.forgetPickedBackup()
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
     * Rückfrage «Ersetzen?»; [password] null bei einer lesbaren Sicherung; [preview] = ungültige Einträge und solche
     * über den Grenzen, die nicht übernommen würden (die Rückfrage nennt sie). Kein data class: kein Passwort in toString.
     */
    class Confirm(
        override val uri: android.net.Uri,
        val password: String?,
        val preview: com.cryptochecker.app.data.RestorePreview = com.cryptochecker.app.data.RestorePreview(),
    ) : RestoreStep {
        /** Ungültige Einträge: Wiederherstellen lässt sie nur mit dieser bestätigten Anzahl weg (`acceptInvalid`). */
        val invalid: Int get() = preview.invalid
        /** Sicherung ohne Passwort: keine Echtheitsprüfung — die Rückfrage warnt davor. */
        val unencrypted: Boolean get() = password == null
    }
}

sealed interface BackupMessage {
    data object Exported : BackupMessage
    /**
     * [appLockKept] = Sicherung ohne Passwort wollte die Portfolio-Sperre ausschalten; sie blieb an.
     * [droppedPairs]/[droppedAlarms] = über den Grenzen nicht übernommen (seit Build 99).
     */
    data class Restored(
        val watches: Int,
        val alarms: Int,
        val appLockKept: Boolean = false,
        val droppedPairs: Int = 0,
        val droppedAlarms: Int = 0,
    ) : BackupMessage
    data object Failed : BackupMessage
    /** Datei über 64 MB oder zu tief verschachtelt ([com.cryptochecker.app.data.BackupLimits]). */
    data object TooLarge : BackupMessage
    /** Zu schreibende Sicherung über 64 MB ([com.cryptochecker.app.data.BackupLimits.ExportTooLargeException]): nicht geschrieben. */
    data object ExportTooLarge : BackupMessage
    /** Sicherung einer neueren App-Version ([com.cryptochecker.app.data.BackupVersion]): nichts übernommen. */
    data object NewerVersion : BackupMessage
    /** Geschriebene Datei liest sich nicht gleich zurück ([com.cryptochecker.app.data.BackupNotVerifiedException]): neue Datei wählen. */
    data object NotVerified : BackupMessage
    /**
     * Zu wenig Arbeitsspeicher für Argon2id ([com.cryptochecker.app.data.BackupCrypto.NotEnoughMemoryException]) oder
     * zum Lesen bzw. Schreiben einer grossen Sicherung ([OutOfMemoryError]).
     */
    data object NotEnoughMemory : BackupMessage
    /** Gespeicherter Stand gerade nicht lesbar ([com.cryptochecker.app.data.BackupStorageKeptException]): nach Neustart. */
    data object StorageKept : BackupMessage
    /** Einspielen in eine Datenbank nur im Arbeitsspeicher ([com.cryptochecker.app.data.BackupStorageInMemoryException]). */
    data object StorageInMemory : BackupMessage
    /** Journal nicht geschrieben ([com.cryptochecker.app.data.BackupJournalUnavailableException]): nichts ersetzt, später erneut. */
    data object JournalUnavailable : BackupMessage

    companion object {
        fun failure(e: Throwable): BackupMessage = when (e) {
            is com.cryptochecker.app.data.BackupLimits.TooLargeException -> TooLarge
            is com.cryptochecker.app.data.BackupCrypto.NotEnoughMemoryException -> NotEnoughMemory
            is com.cryptochecker.app.data.BackupVersion.NewerVersionException -> NewerVersion
            is com.cryptochecker.app.data.BackupNotVerifiedException -> NotVerified
            is com.cryptochecker.app.data.BackupStorageKeptException -> StorageKept
            is com.cryptochecker.app.data.BackupStorageInMemoryException -> StorageInMemory
            is com.cryptochecker.app.data.BackupJournalUnavailableException -> JournalUnavailable
            is com.cryptochecker.app.data.BackupLimits.ExportTooLargeException -> ExportTooLarge
            // Sicherung bis 64 MB als Text und JSON im Speicher (seit Build 93 kein Absturz mehr)
            is OutOfMemoryError -> NotEnoughMemory
            else -> Failed
        }

        /** Haptik zur Meldung: Sicherung bzw. Wiederherstellung fertig = Erfolg, alles andere fehlgeschlagen. */
        fun haptic(message: BackupMessage): HapticEvent = when (message) {
            Exported, is Restored -> HapticEvent.SUCCESS
            else -> HapticEvent.ERROR
        }
    }
}
