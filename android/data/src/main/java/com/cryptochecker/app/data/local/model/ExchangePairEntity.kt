package com.cryptochecker.app.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Ein Handelspaar einer gespeicherten Paarliste (Tabelle «market_pairs»); fällt mit seiner Börse
 * weg. Felder, Reihenfolge und Schlüssel bilden das Schema — nicht verändern (keine Migration).
 */
@Entity(
    tableName = "exchange_pairs",
    primaryKeys = ["exchangeId", "baseAsset", "quoteAsset", "contractType"],
    foreignKeys = [
        ForeignKey(
            entity = ExchangeEntity::class,
            parentColumns = ["id"],
            childColumns = ["exchangeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ExchangePairEntity(
    @ColumnInfo
    val exchangeId: Long,

    @ColumnInfo
    val baseAsset: String,

    @ColumnInfo
    val quoteAsset: String,

    /** Als Name gespeichert (Room-Standard für Enums). */
    @ColumnInfo
    val contractType: FuturesContractType,

    /** Kennung des Paars bei der Börse, falls sie eine eigene führt. */
    @ColumnInfo
    val exchangePairId: String?,

    /** Kein Krypto-Token (Aktie, Rohstoff, Devisen, Pre-IPO). */
    @ColumnInfo(defaultValue = "0")
    val tradFi: Boolean = false,
)
