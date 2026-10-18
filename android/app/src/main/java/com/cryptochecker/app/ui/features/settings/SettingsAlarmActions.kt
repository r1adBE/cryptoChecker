// Alarm- und Mitteilungs-Einstellungen (Teil von `SettingsViewModel`): Kursmitteilung je Paar, Alarmton
// (System-Auswahl oder eigene Datei) und Alarm-Signal.
// Eigene Datei, weil Ton und Kanäle über den Notifier laufen und nicht nur eine Einstellung setzen.
package com.cryptochecker.app.ui.features.settings

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.notification.AlarmSoundStore
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.notification.PriceNotificationRule
import kotlinx.coroutines.launch
import com.cryptochecker.app.notification.alarmChannel

/** Kursmitteilung eines Paars an/aus — wie der Schalter im Aktionsblatt (`WatchlistViewModel.setNotificationEnabled`). */
fun SettingsViewModel.setPairNotification(watchId: Long, enabled: Boolean) {
    viewModelScope.launch { applyPairNotification(watchId, enabled) }
}

/** «Alle an» / «Alle aus» für alle Paare der Merkliste. */
fun SettingsViewModel.setAllPairNotifications(enabled: Boolean) {
    viewModelScope.launch {
        watchRepository.getWatches().filter { it.notificationEnabled != enabled }
            .forEach { applyPairNotification(it.id, enabled) }
    }
}

internal suspend fun SettingsViewModel.applyPairNotification(watchId: Long, enabled: Boolean) {
    watchRepository.setNotificationEnabled(watchId, enabled)
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
}

/** Alarmton aus der System-Auswahl; null = Standardton. */
fun SettingsViewModel.setAlarmSound(uri: android.net.Uri?) = update {
    val name = uri?.let { AlarmSoundStore.title(context, it) }
    applyAlarmSound(uri?.toString(), name)
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
        applyAlarmSound(imported.first.toString(), imported.second)
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
