package com.cryptochecker.app.data.local

import androidx.room.*
import com.cryptochecker.app.data.local.model.MarketEntity
import com.cryptochecker.app.data.local.model.MarketPairEntity
import com.cryptochecker.app.data.local.model.MarketWithPairs
import com.cryptochecker.app.domain.model.MarketPairsInfo

interface MarketDaoBase {
    // ------------- Market data ----------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarket(market: MarketEntity): Long

    @Query("UPDATE markets SET updateDate = :updateDate WHERE id = :marketId")
    suspend fun updateMarketDate(marketId: Long, updateDate: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketPairs(marketPairs: List<MarketPairEntity>)

    @Suppress("SpellCheckingInspection")
    @Transaction
    @Query("SELECT * FROM markets WHERE marketKey = :marketKey COLLATE NOCASE")
    suspend fun getMarketWithPairs(marketKey: String): MarketWithPairs?

    @Suppress("SpellCheckingInspection")
    @Query("SELECT id FROM markets WHERE marketKey = :marketKey COLLATE NOCASE")
    suspend fun getMarketId(marketKey: String): Long?

    @Query("DELETE FROM market_pairs WHERE marketId = :marketId")
    suspend fun deleteMarketPairs(marketId: Long)
}

@Dao
abstract class MarketDao: MarketDaoBase {
    @Transaction
    open suspend fun saveMarketWithPairs(marketKey: String, marketData: MarketPairsInfo): Long {
        var marketId = getMarketId(marketKey) ?: 0L

        if(marketId != 0L) {
            deleteMarketPairs(marketId)
            updateMarketDate(marketId, marketData.lastSyncDate)
        } else {
            marketId = insertMarket(
                MarketEntity(
                    id = marketId,
                    marketKey = marketKey,
                    updateDate = marketData.lastSyncDate
                )
            )
        }

        marketData.pairs.also { pairs ->
            insertMarketPairs(
                pairs.map {
                    MarketPairEntity(
                        marketId = marketId,
                        baseAsset = it.currencyBase,
                        quoteAsset = it.currencyCounter,
                        contractType =it.contractType,
                        marketPairId = it.currencyPairId
                    )
                }
            )
        }

        return marketId
    }
}