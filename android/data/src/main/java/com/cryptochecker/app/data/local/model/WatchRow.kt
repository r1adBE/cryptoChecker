package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Zeile der Tabelle «watches» (Room). Das Fachmodell ohne Room ist [WatchEntity] in :domain; Spalten, Reihenfolge und
 * Standardwerte hier bestimmen das exportierte Schema (app/schemas) und bleiben darum unverändert.
 */
@Entity(
    tableName = "watches",
    // Eindeutig je Paar; `pairId` gehört dazu, damit zwei DexScreener-Pools mit gleichem Symbol auf derselben
    // Chain zwei Einträge sein können (WatchIdentity). Bei den Börsen prüft die App vor dem Einfügen
    // ohne Kennung (WatchDao.findWatchId); SQLite zählt NULL-Kennungen ohnehin als verschieden.
    indices = [Index(value = ["exchangeKey", "baseAsset", "quoteAsset", "contractType", "pairId"], unique = true)]
)
data class WatchRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    @ColumnInfo val exchangeKey: String,
    @ColumnInfo val exchangeName: String,
    @ColumnInfo val baseAsset: String,
    @ColumnInfo val quoteAsset: String,
    @ColumnInfo val contractType: FuturesContractType,
    @ColumnInfo val pairId: String?,
    @ColumnInfo val sortOrder: Int,
    @ColumnInfo val notificationEnabled: Boolean,
    @ColumnInfo val ttsEnabled: Boolean,
    @ColumnInfo val lastPrice: Double?,
    @ColumnInfo val previousPrice: Double?,
    @ColumnInfo val lastUpdate: Long,
    @ColumnInfo val notifiedPrice: Double?,
    @ColumnInfo val notifiedAt: Long,
    @ColumnInfo val lastError: String?,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean,
    @ColumnInfo val groupName: String?,
    @ColumnInfo val note: String?,
    @ColumnInfo val change24h: Double?,
    @ColumnInfo(defaultValue = "0") val disabled: Boolean,
    @ColumnInfo(defaultValue = "0") val priceChangedAt: Long,
    @ColumnInfo val exchangeTime: Long?,
)

fun WatchRow.toDomain(): WatchEntity = WatchEntity(
    id = id,
    exchangeKey = exchangeKey,
    exchangeName = exchangeName,
    baseAsset = baseAsset,
    quoteAsset = quoteAsset,
    contractType = contractType,
    pairId = pairId,
    sortOrder = sortOrder,
    notificationEnabled = notificationEnabled,
    ttsEnabled = ttsEnabled,
    lastPrice = lastPrice,
    previousPrice = previousPrice,
    lastUpdate = lastUpdate,
    notifiedPrice = notifiedPrice,
    notifiedAt = notifiedAt,
    lastError = lastError,
    favorite = favorite,
    groupName = groupName,
    note = note,
    change24h = change24h,
    disabled = disabled,
    priceChangedAt = priceChangedAt,
    exchangeTime = exchangeTime,
)

fun WatchEntity.toRow(): WatchRow = WatchRow(
    id = id,
    exchangeKey = exchangeKey,
    exchangeName = exchangeName,
    baseAsset = baseAsset,
    quoteAsset = quoteAsset,
    contractType = contractType,
    pairId = pairId,
    sortOrder = sortOrder,
    notificationEnabled = notificationEnabled,
    ttsEnabled = ttsEnabled,
    lastPrice = lastPrice,
    previousPrice = previousPrice,
    lastUpdate = lastUpdate,
    notifiedPrice = notifiedPrice,
    notifiedAt = notifiedAt,
    lastError = lastError,
    favorite = favorite,
    groupName = groupName,
    note = note,
    change24h = change24h,
    disabled = disabled,
    priceChangedAt = priceChangedAt,
    exchangeTime = exchangeTime,
)
