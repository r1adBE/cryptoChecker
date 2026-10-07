package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Alle Alarme über alle Paare, nach Paar gruppiert (scharfe zuerst). */
@HiltViewModel
class AlarmsOverviewViewModel @Inject constructor(
    private val watchRepository: WatchRepository,
) : ViewModel() {

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

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch { watchRepository.setAlarmEnabled(alarmId, enabled) }
    }
}
