package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.PairEditResult
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.WatchSnapshot
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.AlarmWithWatch
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.flow.Flow

/**
 * Merkliste und Alarme für die Oberfläche — Schnittstelle ohne Room; Umsetzung in :data (`WatchRepository`, über
 * `WatchStoreAdapter`). Schreibwege des Nutzers lehnen bei gesperrter Merkliste ab (`userWriteRefused`).
 */
interface WatchStore {
    fun observeWatches(): Flow<List<WatchEntity>>
    fun observeWatch(id: Long): Flow<WatchEntity?>
    suspend fun getWatches(): List<WatchEntity>
    suspend fun getWatch(id: Long): WatchEntity?

    /** «Paar bearbeiten»: Börse, Paar und Kontrakt eines Eintrags ändern (derselbe Eintrag). */
    suspend fun changePair(id: Long, exchange: ExchangeInfo, pair: ExchangePair): PairEditResult
    suspend fun setMissingPercentReferences(watchId: Long, price: Double)
    suspend fun deleteWatch(id: Long): Boolean
    suspend fun deleteWatches(ids: Collection<Long>): Boolean
    suspend fun snapshot(id: Long): WatchSnapshot?
    suspend fun deleteAllWatches(): Boolean
    suspend fun setNotificationEnabled(id: Long, enabled: Boolean): Boolean
    suspend fun setFavorite(id: Long, favorite: Boolean): Boolean

    /** Paar verschieben; mit [group] nur unter den Paaren dieser Gruppe, [isAlpha] für die Ansicht «Alpha». */
    suspend fun move(id: Long, move: WatchMove, group: String? = null, isAlpha: (WatchEntity) -> Boolean = { false })
    suspend fun reorder(orderedIds: List<Long>)
    suspend fun setNote(id: Long, note: String?)
    suspend fun setGroup(id: Long, groupName: String?): Boolean
    suspend fun applyGroupEdit(oldName: String?, newName: String, memberIds: Set<Long>)
    suspend fun clearGroup(groupName: String)
    suspend fun setTtsEnabled(id: Long, enabled: Boolean): Boolean
    suspend fun setDisabled(ids: Collection<Long>, disabled: Boolean): Boolean
    suspend fun setNotifiedPrice(id: Long, price: Double?, time: Long = System.currentTimeMillis())
    suspend fun clearChanges(ids: List<Long>)

    fun observeAlarms(watchId: Long): Flow<List<AlarmEntity>>
    fun observeAllAlarms(): Flow<List<AlarmWithWatch>>
    fun observeEnabledAlarmCount(watchId: Long): Flow<Int>
    suspend fun getEnabledAlarms(watchId: Long): List<AlarmEntity>
    suspend fun getAllEnabledAlarms(): List<AlarmEntity>
    suspend fun getAllAlarmIds(): List<Long>
    fun observeWatchIdsWithEnabledAlarms(): Flow<List<Long>>
    suspend fun deleteAlarm(id: Long): Boolean
    suspend fun setAlarmEnabled(id: Long, enabled: Boolean, fromUser: Boolean = true): Boolean

    /** Neuer Alarm nur unter der Obergrenze je Paar; Bearbeiten (feste Id) zählt nicht. */
    suspend fun trySaveAlarm(alarm: AlarmEntity): AlarmSaveResult

    /** Gespeicherten Stand lesen, mit [build] zusammenführen und schreiben — in einer Transaktion. */
    suspend fun trySaveAlarmMerged(id: Long, build: (AlarmEntity?) -> AlarmEntity): AlarmSaveResult

    /** Paar anlegen: angelegt (mit Id), schon vorhanden, Merkliste voll oder gesperrt. */
    suspend fun tryAddWatch(
        exchange: ExchangeInfo,
        pair: ExchangePair,
        group: String? = null,
        notificationEnabled: Boolean = DEFAULT_NOTIFICATION_ENABLED,
    ): AddWatchOutcome

    /** Wie [tryAddWatch]; null, wenn nicht angelegt. */
    suspend fun addWatch(
        exchange: ExchangeInfo,
        pair: ExchangePair,
        group: String? = null,
        notificationEnabled: Boolean = DEFAULT_NOTIFICATION_ENABLED,
    ): Long?

    /** Gelöschtes Paar unverändert zurückholen (gleiche Id, gleicher Platz, alle Alarme). */
    suspend fun restore(snapshot: WatchSnapshot): RestoreOutcome

    /** Rückfrage «Merkliste wird nicht gespeichert» einmal je Nutzeraktion, vor der Aktion; false = «Abbrechen». */
    suspend fun confirmUserWrite(): Boolean

    companion object {
        /** Kurs-Mitteilung neuer Paare: an. Wie iOS `Watch.newPairNotificationEnabled`. */
        const val DEFAULT_NOTIFICATION_ENABLED = true
    }
}
