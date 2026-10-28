package com.cryptochecker.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cryptochecker.app.data.local.model.ExchangeEntity
import com.cryptochecker.app.data.local.model.ExchangePairEntity
import com.cryptochecker.app.data.local.model.ExchangeWithPairs
import com.cryptochecker.app.domain.model.ExchangePairsInfo

/** Zugriff auf die gespeicherten Paarlisten der Börsen (Tabellen «markets» und «market_pairs»). */
@Dao
abstract class ExchangeDao {

    // «Börse» — Kennung ohne Gross-/Kleinschreibung (wie die Spalte)

    @Suppress("SpellCheckingInspection")
    @Query("SELECT id FROM exchanges WHERE exchangeKey = :exchangeKey COLLATE NOCASE")
    abstract suspend fun getExchangeId(exchangeKey: String): Long?

    @Suppress("SpellCheckingInspection")
    @Transaction
    @Query("SELECT * FROM exchanges WHERE exchangeKey = :exchangeKey COLLATE NOCASE")
    abstract suspend fun getExchangeWithPairs(exchangeKey: String): ExchangeWithPairs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertExchange(exchange: ExchangeEntity): Long

    @Query("UPDATE exchanges SET updateDate = :updateDate WHERE id = :exchangeId")
    abstract suspend fun updateExchangeDate(exchangeId: Long, updateDate: Long)

    // «Paare»

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertExchangePairs(exchangePairs: List<ExchangePairEntity>)

    @Query("DELETE FROM exchange_pairs WHERE exchangeId = :exchangeId")
    abstract suspend fun deleteExchangePairs(exchangeId: Long)

    /**
     * Ersetzt die Paarliste einer Börse in einem Zug: Bekannte Börse → alte Paare weg, Datum neu;
     * unbekannte → neuer Eintrag. Liefert die Nummer der Börse.
     */
    @Transaction
    open suspend fun saveExchangeWithPairs(exchangeKey: String, exchangeData: ExchangePairsInfo): Long {
        val known = getExchangeId(exchangeKey)?.takeIf { it != 0L }
        val exchangeId = if (known != null) {
            deleteExchangePairs(known)
            updateExchangeDate(known, exchangeData.lastSyncDate)
            known
        } else {
            insertExchange(ExchangeEntity(id = 0L, exchangeKey = exchangeKey, updateDate = exchangeData.lastSyncDate))
        }

        insertExchangePairs(exchangeData.pairs.map { pair ->
            ExchangePairEntity(
                exchangeId = exchangeId,
                baseAsset = pair.base,
                quoteAsset = pair.quote,
                contractType = pair.contractType,
                exchangePairId = pair.pairId,
                tradFi = pair.tradFi,
            )
        })
        return exchangeId
    }
}
