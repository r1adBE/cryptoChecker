package com.cryptochecker.app.data.local.model

import androidx.room.*
import com.cryptochecker.marketdata.model.FuturesContractType

@Entity(
    tableName = "market_pairs",
    foreignKeys = [
        ForeignKey(
            entity = MarketEntity::class,
            parentColumns = ["id"],
            childColumns = ["marketId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    primaryKeys = ["marketId", "baseAsset", "quoteAsset", "contractType"]
)
data class MarketPairEntity(
    @ColumnInfo
    val marketId: Long,

    @ColumnInfo
    val baseAsset: String,

    @ColumnInfo
    val quoteAsset: String,

    @ColumnInfo
    val contractType: FuturesContractType,

    @ColumnInfo
    val marketPairId: String?,

    /** Kein Krypto-Token (Aktie, Rohstoff, Devisen, Pre-IPO); seit MIGRATION_11_12. */
    @ColumnInfo(defaultValue = "0")
    val tradFi: Boolean = false,
)