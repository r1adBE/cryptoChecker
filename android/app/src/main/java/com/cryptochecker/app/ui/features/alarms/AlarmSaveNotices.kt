// Hinweise nach dem Speichern eines Alarms (Teil von `AlarmsViewModel`): Bestätigung des allerersten Alarms,
// einmaliges Angebot «Pünktlich prüfen» und der Hinweis «Wann Alarme geprüft werden».
// Eigene Klasse, weil diese einmaligen Hinweise eine eigene Aufgabe sind; das ViewModel besitzt sie und gibt
// [scope] (seinen viewModelScope) mit.
package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.alarm.AlarmTimingHint
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class AlarmSaveNotices(
    private val scope: CoroutineScope,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val exactScheduler: com.cryptochecker.app.work.ExactCheckScheduler,
) {
    /**
     * Bestätigung nach dem allerersten Alarm zeigen. Ihr Text ist der Hinweis «Wann Alarme geprüft
     * werden» ([AlarmTimingHint]) und braucht kein Symbol (Build 55).
     */
    private val _firstAlarm = MutableStateFlow(false)
    val firstAlarm: StateFlow<Boolean> = _firstAlarm.asStateFlow()

    fun dismissFirstAlarm() {
        _firstAlarm.value = false
        _exactOffer.value = null
    }

    /** Bestätigung des allerersten Alarms zeigen — mit «Pünktlich prüfen» im selben Dialog (höchstens ein Dialog nach dem ersten Alarm). */
    suspend fun showFirstAlarm() {
        offerExactChecksOnce()
        _firstAlarm.value = true
    }

    /** «Pünktlich prüfen» anbieten (einmal, im Dialog nach dem allerersten Alarm); null = nicht. */
    private val _exactOffer = MutableStateFlow<com.cryptochecker.app.work.ExactCheckOffer.Kind?>(null)
    val exactOffer: StateFlow<com.cryptochecker.app.work.ExactCheckOffer.Kind?> = _exactOffer.asStateFlow()

    private suspend fun offerExactChecksOnce() {
        val kind = com.cryptochecker.app.work.ExactCheckOffer.decide(
            exactOn = settingsRepository.current().exactChecks,
            permitted = exactScheduler.canScheduleExact(),
            offeredBefore = exactScheduler.offerShown,
        ) ?: return
        // Gleich merken: auch «OK» oder Wegtippen fragt nie wieder
        exactScheduler.offerShown = true
        _exactOffer.value = kind
    }

    /** Angebot angenommen: einschalten; Wecker stellen, sobald erlaubt (sonst nach der Berechtigung, BootReceiver). */
    fun acceptExactOffer() {
        _exactOffer.value = null
        scope.launch {
            settingsRepository.setExactChecks(true)
            exactScheduler.sync(true, hasWork = true)
        }
    }

    /**
     * Hinweis «Wann Alarme geprüft werden» jetzt zeigen ([AlarmTimingHint]): einmal nach dem Speichern,
     * nicht zusätzlich zur Bestätigung des allerersten Alarms.
     */
    private val _timingHint = MutableStateFlow(false)
    val timingHint: StateFlow<Boolean> = _timingHint.asStateFlow()

    fun dismissTimingHint() { _timingHint.value = false }

    /**
     * Hinweis prüfen und merken; true = nach dem Speichern zeigen. Nach jedem Speichern gilt er als
     * gezeigt: entweder erscheint er jetzt oder die Bestätigung des ersten Alarms mit demselben Satz.
     */
    suspend fun markTimingHint(firstAlarmDialog: Boolean): Boolean {
        val shown = settingsRepository.current().alarmTimingHintShown
        if (!shown) settingsRepository.setAlarmTimingHintShown(true)
        return AlarmTimingHint.shouldShow(alreadyShown = shown, firstAlarmDialog = firstAlarmDialog)
    }

    /** Nach dem Speichern: Hinweis «Wann Alarme geprüft werden» zeigen ([markTimingHint] hat true geliefert). */
    fun showTimingHint() { _timingHint.value = true }

    /** Bestätigung nach dem allerersten Alarm: true, wenn sie jetzt gezeigt werden soll. */
    suspend fun markFirstAlarm(existing: Boolean): Boolean {
        // Gab es schon Alarme (z. B. vor dem Update), gilt die Bestätigung als gezeigt.
        val shownBefore = existing || settingsRepository.current().firstAlarmShown
        if (shownBefore) return false
        val firstEver = watchRepository.observeAllAlarms().first().isEmpty()
        settingsRepository.setFirstAlarmShown(true)
        return firstEver
    }
}
