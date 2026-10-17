package com.cryptochecker.app.data.local

import android.database.sqlite.SQLiteException
import androidx.annotation.WorkerThread
import com.cryptochecker.app.data.local.model.MarketPairEntity
import com.cryptochecker.app.domain.exceptions.DatabaseError
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.marketdata.model.MarketPair
import javax.inject.Inject

/** Gespeicherte Paarlisten lesen und ersetzen (Room, siehe [MarketDao]). */
class MarketLocalDataSource @Inject constructor(private val dao: MarketDao) {

    /** Gespeicherte Liste der Börse, null wenn noch nie abgeglichen. */
    @WorkerThread
    suspend fun getMarketData(marketKey: String): MarketPairsInfo? =
        dao.getMarketWithPairs(marketKey)?.let { stored ->
            MarketPairsInfo(
                lastSyncDate = stored.market.updateDate,
                pairs = stored.pairs.map { it.toPairInfo() },
            )
        }

    /** Ersetzt die gespeicherte Liste; Fehler der Datenbank kommen als [DatabaseError]. */
    @WorkerThread
    suspend fun saveMarketData(marketKey: String, marketData: MarketPairsInfo) {
        try {
            dao.saveMarketWithPairs(marketKey, marketData)
        } catch (sqlError: SQLiteException) {
            throw DatabaseError(sqlError)
        }
    }
}

private fun MarketPairEntity.toPairInfo(): MarketPair =
    MarketPair(baseAsset, quoteAsset, marketPairId, contractType, tradFi)
