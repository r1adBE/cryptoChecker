package com.cryptochecker.app.data.local.model

import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.WatchEntity

/** Ein Alarm mit seinem Paar (Alarm-Übersicht); gelesen über [AlarmWithWatchRow]. */
data class AlarmWithWatch(
    val alarm: AlarmEntity,
    val watch: WatchEntity,
)
