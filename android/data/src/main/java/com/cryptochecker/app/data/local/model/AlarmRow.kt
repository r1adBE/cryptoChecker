package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.cryptochecker.app.domain.model.AlarmCondition
import com.cryptochecker.app.domain.model.AlarmEntity

/**
 * Zeile der Tabelle «alarms» (Room). Das Fachmodell ohne Room ist [AlarmEntity] in :domain; Spalten, Reihenfolge und
 * Standardwerte hier bestimmen das exportierte Schema (app/schemas) und bleiben darum unverändert.
 */
@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = WatchRow::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("watchId")]
)
data class AlarmRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    @ColumnInfo val watchId: Long,
    @ColumnInfo val condition: AlarmCondition,
    @ColumnInfo val threshold: Double,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val repeating: Boolean,
    @ColumnInfo val sound: Boolean,
    @ColumnInfo val vibrate: Boolean,
    @ColumnInfo val speak: Boolean,
    @ColumnInfo val referencePrice: Double?,
    @ColumnInfo val lastTriggeredAt: Long,
    @ColumnInfo val lastTriggeredPrice: Double?,
    @ColumnInfo(defaultValue = "1") val windowHours: Int,
    @ColumnInfo(defaultValue = "0") val referenceAt: Long,
    @ColumnInfo val currency: String?,
)

fun AlarmRow.toDomain(): AlarmEntity = AlarmEntity(
    id = id,
    watchId = watchId,
    condition = condition,
    threshold = threshold,
    enabled = enabled,
    repeating = repeating,
    sound = sound,
    vibrate = vibrate,
    speak = speak,
    referencePrice = referencePrice,
    lastTriggeredAt = lastTriggeredAt,
    lastTriggeredPrice = lastTriggeredPrice,
    windowHours = windowHours,
    referenceAt = referenceAt,
    currency = currency,
)

fun AlarmEntity.toRow(): AlarmRow = AlarmRow(
    id = id,
    watchId = watchId,
    condition = condition,
    threshold = threshold,
    enabled = enabled,
    repeating = repeating,
    sound = sound,
    vibrate = vibrate,
    speak = speak,
    referencePrice = referencePrice,
    lastTriggeredAt = lastTriggeredAt,
    lastTriggeredPrice = lastTriggeredPrice,
    windowHours = windowHours,
    referenceAt = referenceAt,
    currency = currency,
)

/** Alarm mit seinem Paar, wie Room ihn liest; [toDomain] liefert [AlarmWithWatch]. */
data class AlarmWithWatchRow(
    @Embedded val alarm: AlarmRow,

    @Relation(parentColumn = "watchId", entityColumn = "id")
    val watch: WatchRow,
)

fun AlarmWithWatchRow.toDomain(): AlarmWithWatch = AlarmWithWatch(alarm = alarm.toDomain(), watch = watch.toDomain())
