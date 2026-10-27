package com.cryptochecker.app.data.local.model

import androidx.room.Embedded
import androidx.room.Relation

data class AlarmWithWatch(
    @Embedded val alarm: AlarmEntity,

    @Relation(parentColumn = "watchId", entityColumn = "id")
    val watch: WatchEntity,
)
