// Alarm- und Mitteilungs-Einstellungen (Teil von `SettingsViewModel`): Kursmitteilung je Paar, Alarmton
// (System-Auswahl oder eigene Datei) und Alarm-Signal.
// Eigene Datei, weil Ton und Kanäle über den Notifier laufen und nicht nur eine Einstellung setzen.
package com.cryptochecker.app.ui.features.settings

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.notification.AlarmSoundStore
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.notification.PriceNotificationRule
import kotlinx.coroutines.launch
import com.cryptochecker.app.notification.alarmChannel

/**
 * Kursmitteilung eines Paars an/aus — wie der Schalter im Aktionsblatt (`WatchlistViewModel.setNotificationEnabled`).
 * UX4-R3: [onDone] nach dem Schreiben, true = geschrieben (erst dann die Erlaubnis fragen).
 */
fun SettingsViewModel.setPairNotification(watchId: Long, enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
    // Rückfrage F-08 vorher; «Abbrechen» = nichts
    viewModelScope.launch { onDone(watchRepository.confirmUserWrite() && applyPairNotification(watchId, enabled)) }
}

/** «Alle an» / «Alle aus» für alle Paare der Merkliste; [onDone] wie bei [setPairNotification] (mindestens eins geschrieben). */
fun SettingsViewModel.setAllPairNotifications(enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
    viewModelScope.launch {
        // EINE Rückfrage F-08 für alle Paare (nicht je Paar); «Abbrechen» = nichts
        if (!watchRepository.confirmUserWrite()) return@launch onDone(false)
        onDone(watchRepository.getWatches().filter { it.notificationEnabled != enabled }
            .count { applyPairNotification(it.id, enabled) } > 0)
    }
}

/** true = geschrieben, false = abgelehnt. */
internal suspend fun SettingsViewModel.applyPairNotification(watchId: Long, enabled: Boolean): Boolean {
    // Abgelehnt (Merkliste gesperrt): nichts zeigen bzw. entfernen, das Paar meldet wie bisher
    if (!watchRepository.setNotificationEnabled(watchId, enabled)) return false
    if (enabled) {
        // Einschalten: einmal zeigen und den Bezugspunkt der Melde-Schwelle auf den aktuellen Kurs setzen;
        // deaktiviertes Paar nur merken
        // Nur nach den Einstellungen (PriceNotificationRule): «Alle an» bei ausgeschalteten
        // Kurs-Mitteilungen zeigt nichts; nicht gezeigt → Bezug leer
        watchRepository.getWatch(watchId)?.takeIf { !it.disabled }?.let { updated ->
            val shown = notifier.showPriceNow(updated)
            watchRepository.setNotifiedPrice(updated.id, PriceNotificationRule.referenceAfterEnable(shown, updated.lastPrice))
        }
    } else {
        notifier.cancelPrice(watchId)
        watchRepository.setNotifiedPrice(watchId, null)
    }
    return true
}

/** Alarmton aus der System-Auswahl; null = Standardton. */
fun SettingsViewModel.setAlarmSound(uri: android.net.Uri?) = update {
    val name = uri?.let { AlarmSoundStore.title(context, it) }
    val previous = settingsRepository.current().alarmSoundUri
    applyAlarmSound(uri?.toString(), name)
    deletePreviousOwnTone(previous, uri?.toString())
}

/** Eigene Audiodatei übernehmen (ab Android 10). Meldet Fehler über [soundError]. */
fun SettingsViewModel.importAlarmSound(source: android.net.Uri) = update {
    if (!AlarmSoundStore.supportsCustomFile) return@update
    val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        AlarmSoundStore.importFile(context, source)
    }
    if (imported == null) {
        _soundError.value = true
    } else {
        val previous = settingsRepository.current().alarmSoundUri
        applyAlarmSound(imported.first.toString(), imported.second)
        deletePreviousOwnTone(previous, imported.first.toString())
    }
}

/**
 * Vorigen eigenen Ton aus der Mediathek löschen, sobald ein anderer gilt (sonst sammeln sich die
 * Kopien in «Notifications/CryptoChecker»). Erst nach dem Umstellen: der Kanal zeigt dann schon auf den neuen Ton.
 */
private suspend fun SettingsViewModel.deletePreviousOwnTone(previous: String?, next: String?) {
    if (!AlarmSoundStore.supportsCustomFile) return
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        AlarmSoundStore.deletePreviousIfOwn(context, previous, next)
    }
}

internal suspend fun SettingsViewModel.applyAlarmSound(uri: String?, name: String?) {
    val version = settingsRepository.setAlarmSound(uri, name)
    NotificationChannels.ensureAlarmChannel(context, version, uri)
    // Kanal des gewählten Signals gleich mit dem neuen Ton anlegen
    val signal = settingsRepository.current().alarmSignal
    if (signal != AlarmSignal.SYSTEM) NotificationChannels.ensureAlarmChannel(context, signal, version, uri)
}

/**
 * «Alarm-Signal» wählen. Legt den Kanal gleich an (damit «In den Systemeinstellungen
 * anpassen» ihn findet); die Kanäle der anderen Signale bleiben bestehen.
 */
fun SettingsViewModel.setAlarmSignal(signal: AlarmSignal) = update {
    settingsRepository.setAlarmSignal(signal)
    notifier.alarmChannel(signal)
}

/** Kanal-ID des gewählten «Alarm-Signals» (angelegt) — für die Systemeinstellungen des Kanals. */
fun SettingsViewModel.currentAlarmChannelId(): String = notifier.alarmChannel(settings.value.alarmSignal)
