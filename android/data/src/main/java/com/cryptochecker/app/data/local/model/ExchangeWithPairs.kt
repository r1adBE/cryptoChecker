package com.cryptochecker.app.data.local.model

import androidx.room.Embedded
import androidx.room.Relation

/** Börse samt allen ihren gespeicherten Paaren (eine Abfrage, siehe ExchangeDao.getExchangeWithPairs). */
data class ExchangeWithPairs(
    @Embedded
    val exchange: ExchangeEntity,

    @Relation(
        entity = ExchangePairEntity::class,
        parentColumn = "id",
        entityColumn = "exchangeId",
    )
    val pairs: List<ExchangePairEntity>,
)
