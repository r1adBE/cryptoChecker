package com.cryptochecker.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import com.cryptochecker.app.data.local.model.WatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {

    // ---------------- Watchlist ----------------

    // Favoriten zuerst, danach in der gewohnten Reihenfolge.
    @Query("SELECT * FROM watches ORDER BY favorite DESC, sortOrder ASC, id ASC")
    fun observeWatches(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches ORDER BY favorite DESC, sortOrder ASC, id ASC")
    suspend fun getWatches(): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE id = :id")
    suspend fun getWatch(id: Long): WatchEntity?

    @Query("SELECT * FROM watches WHERE id = :id")
    fun observeWatch(id: Long): Flow<WatchEntity?>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM watches")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWatch(watch: WatchEntity): Long

    @Update
    suspend fun updateWatch(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun deleteWatch(id: Long)

    /** Alarme hängen per Fremdschlüssel daran und werden mit gelöscht. */
    @Query("DELETE FROM watches")
    suspend fun deleteAllWatches()

    @Query(
        """
        SELECT id FROM watches
        WHERE marketKey = :marketKey AND baseAsset = :base
          AND quoteAsset = :quote AND contractType = :contractType
        """
    )
    suspend fun findWatchId(
        marketKey: String,
        base: String,
        quote: String,
        contractType: String,
    ): Long?

    @Query(
        """
        UPDATE watches
        SET previousPrice = lastPrice, lastPrice = :price,
            lastUpdate = :time, lastError = NULL, change24h = :change24h
        WHERE id = :id
        """
    )
    suspend fun updatePrice(id: Long, price: Double, time: Long, change24h: Double?)

    /**
     * Veränderung nachtragen (später Bezug der Tages-Basis) — nur, wenn noch derselbe Kurs
     * ([time]) ohne Veränderung gespeichert ist. @return Anzahl geänderter Zeilen (0 oder 1)
     */
    @Query("UPDATE watches SET change24h = :change24h WHERE id = :id AND lastUpdate = :time AND change24h IS NULL")
    suspend fun fillChange(id: Long, time: Long, change24h: Double): Int

    /** Veränderung entfernen (neue %-Basis oder neuer Tag, aber kein neuer Kurs). */
    @Query("UPDATE watches SET change24h = NULL WHERE id IN (:ids)")
    suspend fun clearChanges(ids: List<Long>)

    /**
     * Nur den Fehler setzen — lastUpdate bleibt beim letzten ERFOLGREICHEN Kurs.
     * Sonst stünde bei «keine Verbindung» fälschlich «gerade eben / aktuell».
     */
    @Query("UPDATE watches SET lastError = :error WHERE id = :id")
    suspend fun updateError(id: Long, error: String?)

    @Query("UPDATE watches SET notifiedPrice = :price, notifiedAt = :time WHERE id = :id")
    suspend fun setNotifiedPrice(id: Long, price: Double?, time: Long)

    @Query("UPDATE watches SET notificationEnabled = :enabled WHERE id = :id")
    suspend fun setNotificationEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE watches SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE watches SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE watches SET ttsEnabled = :enabled WHERE id = :id")
    suspend fun setTtsEnabled(id: Long, enabled: Boolean)

    /** Alter Bestand je Paar: nach der Übernahme ins Portfolio leeren (Spalte bleibt). */
    @Query("UPDATE watches SET holdings = NULL WHERE holdings IS NOT NULL")
    suspend fun clearAllHoldings()

    @Query("UPDATE watches SET groupName = :groupName WHERE id = :id")
    suspend fun setGroup(id: Long, groupName: String?)

    @Query("UPDATE watches SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    /** Gruppe auflösen: alle ihre Paare verlieren die Gruppe, bleiben aber in der Merkliste. */
    @Query("UPDATE watches SET groupName = NULL WHERE groupName = :groupName")
    suspend fun clearGroup(groupName: String)

    // ---------------- Alarme ----------------

    @Query("SELECT * FROM alarms WHERE watchId = :watchId ORDER BY id ASC")
    fun observeAlarms(watchId: Long): Flow<List<AlarmEntity>>

    @Transaction
    @Query("SELECT * FROM alarms ORDER BY id ASC")
    fun observeAllAlarms(): Flow<List<AlarmWithWatch>>

    /** Alle Alarme eines Paars (auch ausgeschaltete), z. B. für «Rückgängig» nach dem Löschen. */
    @Query("SELECT * FROM alarms WHERE watchId = :watchId ORDER BY id ASC")
    suspend fun getAlarms(watchId: Long): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE watchId = :watchId AND enabled = 1")
    suspend fun getEnabledAlarms(watchId: Long): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getAllEnabledAlarms(): List<AlarmEntity>

    @Query("SELECT COUNT(*) FROM alarms WHERE watchId = :watchId AND enabled = 1")
    fun observeEnabledAlarmCount(watchId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: AlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarm(id: Long)

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setAlarmEnabled(id: Long, enabled: Boolean)

    /**
     * Ausgelösten Alarm speichern — nur, wenn er seit dem Lesen unverändert ist (eingeschaltet,
     * gleiche Bedingung, Schwelle, Fenster und Wiederholung); sonst bleibt die Änderung des
     * Nutzers. Rückgabe: geänderte Zeilen (0 = nicht gespeichert, nicht melden).
     */
    @Query(
        """
        UPDATE alarms
        SET lastTriggeredAt = :time, lastTriggeredPrice = :price,
            referencePrice = :referencePrice, referenceAt = :referenceAt, enabled = :enabled
        WHERE id = :id AND enabled = 1 AND `condition` = :condition AND threshold = :threshold
            AND windowHours = :windowHours AND repeating = :repeating
        """
    )
    suspend fun markAlarmTriggered(
        id: Long,
        time: Long,
        price: Double,
        referencePrice: Double?,
        referenceAt: Long,
        enabled: Boolean,
        condition: String,
        threshold: Double,
        windowHours: Int,
        repeating: Boolean,
    ): Int

    /** Neuer Bezug (Bewegungs-/Prozentalarm): Kurs und Beginn setzen — nur bei unverändertem Alarm. */
    @Query(
        "UPDATE alarms SET referencePrice = :price, referenceAt = :time WHERE id = :id AND enabled = 1 " +
            "AND `condition` = :condition AND threshold = :threshold AND windowHours = :windowHours"
    )
    suspend fun setAlarmReferenceIfUnchanged(id: Long, price: Double, time: Long, condition: String, threshold: Double, windowHours: Int): Int

    /**
     * «Nahe am Hoch/Tief», Kursmarken (PRICE_ABOVE/PRICE_BELOW), Funding und Open Interest: wieder
     * scharf stellen (`referenceAt` = 0). Die gemeldete Marke von «Nahe am Hoch/Tief» in
     * `referencePrice` bleibt (NearExtreme.reportedMark: kein zweites «neues Hoch» am selben Tag);
     * die übrigen haben ohnehin keinen Bezugskurs. Andere Alarme bleiben unberührt.
     */
    @Query(
        "UPDATE alarms SET referenceAt = 0 " +
            "WHERE id = :id AND `condition` IN ('NEAR_HIGH', 'NEAR_LOW', 'PRICE_ABOVE', 'PRICE_BELOW', " +
            "'FUNDING_ABOVE', 'FUNDING_BELOW', 'OI_UP', 'OI_DOWN')"
    )
    suspend fun rearmAlarm(id: Long)

    /** Wie [rearmAlarm], aber nur bei unverändertem, eingeschaltetem Alarm (Aktualisierung). */
    @Query(
        "UPDATE alarms SET referenceAt = 0 " +
            "WHERE id = :id AND enabled = 1 AND `condition` = :condition AND threshold = :threshold " +
            "AND windowHours = :windowHours AND `condition` IN ('NEAR_HIGH', 'NEAR_LOW', 'PRICE_ABOVE', 'PRICE_BELOW', " +
            "'FUNDING_ABOVE', 'FUNDING_BELOW', 'OI_UP', 'OI_DOWN')"
    )
    suspend fun rearmAlarmIfUnchanged(id: Long, condition: String, threshold: Double, windowHours: Int): Int

    @Query("SELECT * FROM alarms ORDER BY id ASC")
    suspend fun getAllAlarms(): List<AlarmEntity>

    /**
     * Paar bearbeitet (andere Börse/anderes Paar): Bezüge, die am alten Kurs hängen, verwerfen —
     * Prozent- und Bewegungs-Alarme beginnen beim neuen Kurs, «Nahe am Hoch/Tief», Funding und
     * Open Interest sind wieder scharf. Kursmarken und Volumen-Spikes bleiben unberührt (Schwellen
     * prüft der Nutzer).
     */
    @Query(
        "UPDATE alarms SET referencePrice = NULL, referenceAt = 0 WHERE watchId = :watchId AND `condition` IN " +
            "('CHANGE_PERCENT_UP', 'CHANGE_PERCENT_DOWN', 'MOVE_PERCENT_WINDOW', 'NEAR_HIGH', 'NEAR_LOW', " +
            "'FUNDING_ABOVE', 'FUNDING_BELOW', 'OI_UP', 'OI_DOWN')"
    )
    suspend fun resetPriceReferences(watchId: Long)

    /** Prozentalarme ohne Bezug (nach [resetPriceReferences]) messen ab [price]. */
    @Query(
        "UPDATE alarms SET referencePrice = :price WHERE watchId = :watchId AND referencePrice IS NULL " +
            "AND `condition` IN ('CHANGE_PERCENT_UP', 'CHANGE_PERCENT_DOWN')"
    )
    suspend fun setMissingPercentReferences(watchId: Long, price: Double)
}
