package com.cryptochecker.app.data.local

import android.database.sqlite.SQLiteException
import androidx.annotation.WorkerThread
import com.cryptochecker.app.data.local.model.ExchangePairEntity
import com.cryptochecker.app.domain.exceptions.DatabaseError
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.marketdata.model.ExchangePair
import javax.inject.Inject

/** Gespeicherte Paarlisten lesen und ersetzen (Room, siehe [ExchangeDao]). */
class ExchangeLocalDataSource @Inject constructor(private val dao: ExchangeDao) {

    /** Gespeicherte Liste der Börse, null wenn noch nie abgeglichen. */
    @WorkerThread
    suspend fun getExchangeData(exchangeKey: String): ExchangePairsInfo? =
        dao.getExchangeWithPairs(exchangeKey)?.let { stored ->
            ExchangePairsInfo(
                lastSyncDate = stored.exchange.updateDate,
                pairs = stored.pairs.map { it.toPairInfo() },
            )
        }

    /** Ersetzt die gespeicherte Liste; Fehler der Datenbank kommen als [DatabaseError]. */
    @WorkerThread
    suspend fun saveExchangeData(exchangeKey: String, exchangeData: ExchangePairsInfo) {
        try {
            dao.saveExchangeWithPairs(exchangeKey, exchangeData)
        } catch (sqlError: SQLiteException) {
            throw DatabaseError(sqlError)
        }
    }
}

private fun ExchangePairEntity.toPairInfo(): ExchangePair =
    ExchangePair(baseAsset, quoteAsset, exchangePairId, contractType, tradFi)
