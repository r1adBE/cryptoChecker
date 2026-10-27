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
    tableName = "market_pairs",
    primaryKeys = ["marketId", "baseAsset", "quoteAsset", "contractType"],
    foreignKeys = [
        ForeignKey(
            entity = MarketEntity::class,
            parentColumns = ["id"],
            childColumns = ["marketId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MarketPairEntity(
    @ColumnInfo
    val marketId: Long,

    @ColumnInfo
    val baseAsset: String,

    @ColumnInfo
    val quoteAsset: String,

    /** Als Name gespeichert (Room-Standard für Enums). */
    @ColumnInfo
    val contractType: FuturesContractType,

    /** Kennung des Paars bei der Börse, falls sie eine eigene führt. */
    @ColumnInfo
    val marketPairId: String?,

    /** Kein Krypto-Token (Aktie, Rohstoff, Devisen, Pre-IPO); Spalte seit MIGRATION_11_12. */
    @ColumnInfo(defaultValue = "0")
    val tradFi: Boolean = false,
)
