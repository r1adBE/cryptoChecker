package com.cryptochecker.app.data.local

import android.database.sqlite.SQLiteException
import androidx.annotation.WorkerThread
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.app.domain.exceptions.DatabaseError
import com.cryptochecker.app.domain.model.MarketPairsInfo
import javax.inject.Inject

class MarketLocalDataSource @Inject constructor(private val appDao: MarketDao) {

    @WorkerThread
    suspend fun getMarketData(marketKey: String): MarketPairsInfo? {
        val marketData = appDao.getMarketWithPairs(marketKey) ?: return null

        return MarketPairsInfo(
            marketData.market.updateDate,
            marketData.pairs.map { CurrencyPairInfo(
                it.baseAsset,
                it.quoteAsset,
                it.marketPairId,
                it.contractType
            ) })
    }

    @WorkerThread
    suspend fun saveMarketData(marketKey: String, marketData: MarketPairsInfo) {
        try {
            appDao.saveMarketWithPairs(marketKey, marketData)
        } catch (ex: SQLiteException) {
            throw DatabaseError(ex)
        }
    }
}

