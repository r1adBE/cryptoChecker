package com.cryptochecker.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cryptochecker.app.data.local.model.MarketEntity
import com.cryptochecker.app.data.local.model.MarketPairEntity
import com.cryptochecker.app.data.local.model.MarketWithPairs
import com.cryptochecker.app.domain.model.MarketPairsInfo

/** Zugriff auf die gespeicherten Paarlisten der Börsen (Tabellen «markets» und «market_pairs»). */
@Dao
abstract class MarketDao {

    // «Börse» — Kennung ohne Gross-/Kleinschreibung (wie die Spalte)

    @Suppress("SpellCheckingInspection")
    @Query("SELECT id FROM markets WHERE marketKey = :marketKey COLLATE NOCASE")
    abstract suspend fun getMarketId(marketKey: String): Long?

    @Suppress("SpellCheckingInspection")
    @Transaction
    @Query("SELECT * FROM markets WHERE marketKey = :marketKey COLLATE NOCASE")
    abstract suspend fun getMarketWithPairs(marketKey: String): MarketWithPairs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMarket(market: MarketEntity): Long

    @Query("UPDATE markets SET updateDate = :updateDate WHERE id = :marketId")
    abstract suspend fun updateMarketDate(marketId: Long, updateDate: Long)

    // «Paare»

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMarketPairs(marketPairs: List<MarketPairEntity>)

    @Query("DELETE FROM market_pairs WHERE marketId = :marketId")
    abstract suspend fun deleteMarketPairs(marketId: Long)

    /**
     * Ersetzt die Paarliste einer Börse in einem Zug: Bekannte Börse → alte Paare weg, Datum neu;
     * unbekannte → neuer Eintrag. Liefert die Nummer der Börse.
     */
    @Transaction
    open suspend fun saveMarketWithPairs(marketKey: String, marketData: MarketPairsInfo): Long {
        val known = getMarketId(marketKey)?.takeIf { it != 0L }
        val marketId = if (known != null) {
            deleteMarketPairs(known)
            updateMarketDate(known, marketData.lastSyncDate)
            known
        } else {
            insertMarket(MarketEntity(id = 0L, marketKey = marketKey, updateDate = marketData.lastSyncDate))
        }

        insertMarketPairs(marketData.pairs.map { pair ->
            MarketPairEntity(
                marketId = marketId,
                baseAsset = pair.base,
                quoteAsset = pair.quote,
                contractType = pair.contractType,
                marketPairId = pair.pairId,
                tradFi = pair.tradFi,
            )
        })
        return marketId
    }
}
