package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.toAlarmReading
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioAlarmPause
import com.cryptochecker.app.widget.PortfolioSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Alle Alarme über alle Paare, nach Paar gruppiert (scharfe zuerst). */
@HiltViewModel
class AlarmsOverviewViewModel @Inject constructor(
    private val watchRepository: WatchRepository,
    private val portfolioAlarmRepository: PortfolioAlarmRepository,
    settingsRepository: SettingsRepository,
    /** Mitteilung eines ausgeschalteten Alarms entfernen ([AlarmUserChange]). */
    private val appNotifier: com.cryptochecker.app.notification.AppNotifier,
    /** Letzte Momentaufnahme: warum Portfolio-Alarme gerade nicht prüfen ([portfolioPause]). */
    private val snapshotStore: PortfolioSnapshotStore,
) : ViewModel() {

    /** Alarme «Portfolio-Wert» — nur mit eingeschaltetem Portfolio (sonst prüft sie auch niemand). */
    val portfolioAlarms: StateFlow<List<PortfolioAlarmEntity>> = combine(
        portfolioAlarmRepository.observeAll(),
        settingsRepository.settings,
    ) { alarms, settings -> if (settings.portfolioEnabled) alarms else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Hinweis «Portfolio-Alarme pausiert» ([PortfolioAlarmLogic.pause]) aus der letzten Momentaufnahme; null = keiner
     * (keine eingeschalteten Portfolio-Alarme, keine Aufnahme oder nichts blockiert). Gelesen beim Öffnen und bei jeder
     * Änderung der Alarme.
     */
    val portfolioPause: StateFlow<PortfolioAlarmPause?> = portfolioAlarms
        .map { alarms ->
            val kinds = alarms.filter { it.enabled }.map { it.kind }
            if (kinds.isEmpty()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    snapshotStore.snapshot()?.let { s -> PortfolioAlarmLogic.pause(kinds, s.toAlarmReading(), s.missingCoins, s.staleCoins) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Beträge der Portfolio-Alarme verbergen («Beträge verbergen») und %-Basis für die Sätze. */
    val portfolioDisplay: StateFlow<Pair<Boolean, ChangeBasis>> = settingsRepository.settings
        .map { it.hidePortfolioAmounts to it.changeBasis.storage }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            settingsRepository.cached.hidePortfolioAmounts to settingsRepository.cached.changeBasis.storage
        )

    fun setPortfolioAlarmEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { portfolioAlarmRepository.setEnabled(id, enabled) }
    }

    fun deletePortfolioAlarm(id: Long) {
        viewModelScope.launch { portfolioAlarmRepository.delete(id) }
    }

    val groups: StateFlow<List<List<AlarmWithWatch>>> = watchRepository.observeAllAlarms()
        .map { all ->
            all.groupBy { it.watch.id }
                .values
                .map { group -> group.sortedWith(compareByDescending<AlarmWithWatch> { it.alarm.enabled }.thenBy { it.alarm.id }) }
                .sortedWith(
                    compareByDescending<List<AlarmWithWatch>> { g -> g.any { it.alarm.enabled } }
                        .thenBy { it.first().watch.displayName.lowercase() }
                )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Paare der Merkliste (für «Neuer Alarm» in der leeren Übersicht: Paar wählen). */
    val watches: StateFlow<List<com.cryptochecker.app.data.local.model.WatchEntity>> = watchRepository.observeWatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Zeilen der Paarwahl ([AlarmPickerRow]) ohne Kurs, im Hintergrund gerechnet und nur bei echter Änderung weitergegeben
     * (nicht bei jeder Kurs-Schreibung) — seit Build 104. null = noch nicht gerechnet.
     */
    val pickerRows: StateFlow<List<AlarmPickerRow>?> = watchRepository.observeWatches()
        .map { alarmPickerRows(it) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch {
            // Abgelehnt (Merkliste gesperrt): Alarm bleibt scharf, seine Mitteilung bleibt
            if (!watchRepository.setAlarmEnabled(alarmId, enabled)) return@launch
            // Ausgeschaltet: Mitteilung weg, ihr «1 h später» schaltete ihn sonst wieder ein
            if (AlarmUserChange.cancelsNotification(enabled)) appNotifier.retractAlarm(alarmId)
        }
    }
}
