package com.cryptochecker.app.data

import com.cryptochecker.app.domain.BulkAddRunner
import com.cryptochecker.app.domain.BulkAddWrites
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.util.appScope
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.CoroutineDispatcher

/** Sammelaufnahme im Betrieb: Schreibwege über das Repository, Bereich des Prozesses. */
fun repositoryBulkAddRunner(
    watchRepository: WatchRepository,
    addMoments: AddMoments,
    defaultDispatcher: CoroutineDispatcher,
): BulkAddRunner = BulkAddRunner(RepositoryBulkAddWrites(watchRepository, addMoments), appScope(defaultDispatcher, "Paare aufnehmen"))

private class RepositoryBulkAddWrites(
    private val watchRepository: WatchRepository,
    private val addMoments: AddMoments,
) : BulkAddWrites {
    override suspend fun confirm(): Boolean = watchRepository.confirmUserWrite()

    override suspend fun add(exchange: ExchangeInfo, pairs: List<ExchangePair>, group: String?, onProgress: (Int) -> Unit) =
        watchRepository.addWatches(exchange, pairs, group, onProgress)

    override suspend fun deleteWithoutAlarms(ids: List<Long>) {
        watchRepository.deleteWatchesWithoutAlarms(ids)
    }

    override suspend fun markFirstPairAdded() = addMoments.markFirstPairAdded()
}
