package com.cryptochecker.app.data.local.model

import androidx.room.Embedded
import androidx.room.Relation

/** Börse samt allen ihren gespeicherten Paaren (eine Abfrage, siehe MarketDao.getMarketWithPairs). */
data class MarketWithPairs(
    @Embedded
    val market: MarketEntity,

    @Relation(
        entity = MarketPairEntity::class,
        parentColumn = "id",
        entityColumn = "marketId",
    )
    val pairs: List<MarketPairEntity>,
)
