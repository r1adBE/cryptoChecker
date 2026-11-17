package com.cryptochecker.app.data

import com.cryptochecker.app.domain.PairEditResult
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.WatchSnapshot
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.AddWatchOutcome
import com.cryptochecker.app.domain.watch.AlarmSaveResult
import com.cryptochecker.app.domain.watch.RestoreOutcome
import com.cryptochecker.app.domain.watch.WatchStore
import com.cryptochecker.marketdata.model.ExchangePair
import javax.inject.Inject
import javax.inject.Singleton

/** [WatchStore] der Oberfläche über [WatchRepository] (Room); reicht nur weiter, keine eigene Logik. */
@Singleton
class WatchStoreAdapter @Inject constructor(private val repo: WatchRepository) : WatchStore {
    override fun observeWatches() = repo.observeWatches()
    override fun observeWatch(id: Long) = repo.observeWatch(id)
    override suspend fun getWatches() = repo.getWatches()
    override suspend fun getWatch(id: Long) = repo.getWatch(id)
    override suspend fun changePair(id: Long, exchange: ExchangeInfo, pair: ExchangePair): PairEditResult =
        repo.changePair(id, exchange, pair)
    override suspend fun setMissingPercentReferences(watchId: Long, price: Double) = repo.setMissingPercentReferences(watchId, price)
    override suspend fun deleteWatch(id: Long) = repo.deleteWatch(id)
    override suspend fun deleteWatches(ids: Collection<Long>) = repo.deleteWatches(ids)
    override suspend fun snapshot(id: Long): WatchSnapshot? = repo.snapshot(id)
    override suspend fun deleteAllWatches() = repo.deleteAllWatches()
    override suspend fun setNotificationEnabled(id: Long, enabled: Boolean) = repo.setNotificationEnabled(id, enabled)
    override suspend fun setFavorite(id: Long, favorite: Boolean) = repo.setFavorite(id, favorite)
    override suspend fun move(id: Long, move: WatchMove, group: String?, isAlpha: (WatchEntity) -> Boolean) =
        repo.move(id, move, group, isAlpha)
    override suspend fun reorder(orderedIds: List<Long>) = repo.reorder(orderedIds)
    override suspend fun setNote(id: Long, note: String?) = repo.setNote(id, note)
    override suspend fun setGroup(id: Long, groupName: String?) = repo.setGroup(id, groupName)
    override suspend fun applyGroupEdit(oldName: String?, newName: String, memberIds: Set<Long>) =
        repo.applyGroupEdit(oldName, newName, memberIds)
    override suspend fun clearGroup(groupName: String) = repo.clearGroup(groupName)
    override suspend fun setTtsEnabled(id: Long, enabled: Boolean) = repo.setTtsEnabled(id, enabled)
    override suspend fun setDisabled(ids: Collection<Long>, disabled: Boolean) = repo.setDisabled(ids, disabled)
    override suspend fun setNotifiedPrice(id: Long, price: Double?, time: Long) = repo.setNotifiedPrice(id, price, time)
    override suspend fun clearChanges(ids: List<Long>) = repo.clearChanges(ids)
    override fun observeAlarms(watchId: Long) = repo.observeAlarms(watchId)
    override fun observeAllAlarms() = repo.observeAllAlarms()
    override fun observeEnabledAlarmCount(watchId: Long) = repo.observeEnabledAlarmCount(watchId)
    override suspend fun getEnabledAlarms(watchId: Long) = repo.getEnabledAlarms(watchId)
    override suspend fun getAllEnabledAlarms() = repo.getAllEnabledAlarms()
    override suspend fun getAllAlarmIds() = repo.getAllAlarmIds()
    override fun observeWatchIdsWithEnabledAlarms() = repo.observeWatchIdsWithEnabledAlarms()
    override suspend fun deleteAlarm(id: Long) = repo.deleteAlarm(id)
    override suspend fun setAlarmEnabled(id: Long, enabled: Boolean, fromUser: Boolean) = repo.setAlarmEnabled(id, enabled, fromUser)
    override suspend fun trySaveAlarm(alarm: AlarmEntity): AlarmSaveResult = repo.trySaveAlarm(alarm)
    override suspend fun trySaveAlarmMerged(id: Long, build: (AlarmEntity?) -> AlarmEntity): AlarmSaveResult =
        repo.trySaveAlarmMerged(id, build)
    override suspend fun tryAddWatch(
        exchange: ExchangeInfo,
        pair: ExchangePair,
        group: String?,
        notificationEnabled: Boolean
    ): AddWatchOutcome =
        repo.tryAddWatch(exchange, pair, group, notificationEnabled)
    override suspend fun addWatch(exchange: ExchangeInfo, pair: ExchangePair, group: String?, notificationEnabled: Boolean): Long? =
        repo.addWatch(exchange, pair, group, notificationEnabled)
    override suspend fun restore(snapshot: WatchSnapshot): RestoreOutcome = repo.restore(snapshot)
    override suspend fun confirmUserWrite() = repo.confirmUserWrite()
}
