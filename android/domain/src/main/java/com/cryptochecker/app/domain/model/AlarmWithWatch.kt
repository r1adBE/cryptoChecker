package com.cryptochecker.app.domain.model

/** Ein Alarm mit seinem Paar (Alarm-Übersicht); gelesen über `AlarmWithWatchRow` in :data. */
data class AlarmWithWatch(
    val alarm: AlarmEntity,
    val watch: WatchEntity,
)
