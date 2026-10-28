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

    /** Anzahl Paare der Merkliste (Obergrenze [com.cryptochecker.app.domain.watch.WatchlistLimits]). */
    @Query("SELECT COUNT(*) FROM watches")
    suspend fun countWatches(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWatch(watch: WatchEntity): Long

    @Update
    suspend fun updateWatch(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun deleteWatch(id: Long)

    /** Alarme hängen per Fremdschlüssel daran und werden mit gelöscht. */
    @Query("DELETE FROM watches")
    suspend fun deleteAllWatches()

    /**
     * Eintrag mit diesem Schlüssel ([com.cryptochecker.app.domain.watch.WatchIdentity]); [pool] (nur DexScreener,
     * sonst null) grenzt zusätzlich auf die Paar-Kennung ein — gleiche Symbole, anderer Pool = anderer Eintrag.
     */
    @Query(
        """
        SELECT id FROM watches
        WHERE exchangeKey = :exchangeKey AND baseAsset = :base
          AND quoteAsset = :quote AND contractType = :contractType
          AND (:pool IS NULL OR pairId = :pool)
        LIMIT 1
        """
    )
    suspend fun findWatchId(
        exchangeKey: String,
        base: String,
        quote: String,
        contractType: String,
        pool: String?,
    ): Long?

    /**
     * Kurs speichern — nur, wenn er nicht älter ist als der gespeicherte
     * ([com.cryptochecker.app.domain.refresh.PriceWriteOrder]: `lastUpdate <= :time`; ein mehr als eine Stunde in der
     * Zukunft liegender gespeicherter Zeitpunkt gilt als unplausibel).
     * @return geänderte Zeilen (0 oder 1)
     */
    @Query(
        """
        UPDATE watches
        SET previousPrice = lastPrice, lastPrice = :price,
            lastUpdate = :time, lastError = NULL, change24h = :change24h,
            priceChangedAt = :priceChangedAt, exchangeTime = :exchangeTime
        WHERE id = :id AND (lastUpdate <= :time OR lastUpdate > :plausibleUntil)
        """
    )
    suspend fun updatePrice(
        id: Long,
        price: Double,
        time: Long,
        change24h: Double?,
        /** Letzte echte Kursänderung (`PriceFreshness.nextPriceChangedAt`). */
        priceChangedAt: Long,
        /** Zeit laut Börse, null = keine. */
        exchangeTime: Long?,
        /** `PriceWriteOrder.plausibleUntil` zu jetzt. */
        plausibleUntil: Long,
    ): Int

    /**
     * Wie [updatePrice], aber nur, wenn der Eintrag noch DASSELBE Paar ist (Börse, Basis, Quote,
     * Kontrakt, Kennung — `IS`, weil die Kennung null sein darf). Nach «Paar bearbeiten» oder dem
     * Wiederherstellen einer Sicherung (gleiche Id, anderes Paar) wird ein verspäteter Kurs des alten
     * Paars so verworfen, statt zum Bezug des neuen zu werden. Ebenso ein älterer Kurs als der gespeicherte
     * (`PriceWriteOrder`, wie [updatePrice]). @return geänderte Zeilen (0 oder 1)
     */
    @Query(
        """
        UPDATE watches
        SET previousPrice = lastPrice, lastPrice = :price,
            lastUpdate = :time, lastError = NULL, change24h = :change24h,
            priceChangedAt = :priceChangedAt, exchangeTime = :exchangeTime
        WHERE id = :id AND exchangeKey = :exchangeKey AND baseAsset = :base AND quoteAsset = :quote
            AND contractType = :contractType AND pairId IS :pairId
            AND (lastUpdate <= :time OR lastUpdate > :plausibleUntil)
        """
    )
    suspend fun updatePriceForPair(
        id: Long,
        price: Double,
        time: Long,
        change24h: Double?,
        priceChangedAt: Long,
        exchangeTime: Long?,
        plausibleUntil: Long,
        exchangeKey: String,
        base: String,
        quote: String,
        contractType: String,
        pairId: String?,
    ): Int

    /** Wie [updateError], aber nur für dasselbe Paar (siehe [updatePriceForPair]). */
    @Query(
        """
        UPDATE watches SET lastError = :error
        WHERE id = :id AND exchangeKey = :exchangeKey AND baseAsset = :base AND quoteAsset = :quote
            AND contractType = :contractType AND pairId IS :pairId
        """
    )
    suspend fun updateErrorForPair(
        id: Long,
        error: String?,
        exchangeKey: String,
        base: String,
        quote: String,
        contractType: String,
        pairId: String?,
    ): Int

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

    /** Paare deaktivieren bzw. wieder aktivieren (Aktionsblatt, Mehrfachauswahl). */
    @Query("UPDATE watches SET disabled = :disabled WHERE id IN (:ids)")
    suspend fun setDisabled(ids: List<Long>, disabled: Boolean)

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

    /** Welche dieser Paare haben mindestens einen Alarm (auch ausgeschaltet)? Für «Rückgängig» nach «Mehrere Paare». */
    @Query("SELECT DISTINCT watchId FROM alarms WHERE watchId IN (:watchIds)")
    suspend fun getWatchIdsWithAlarms(watchIds: List<Long>): List<Long>

    @Query("SELECT * FROM alarms WHERE watchId = :watchId AND enabled = 1")
    suspend fun getEnabledAlarms(watchId: Long): List<AlarmEntity>

    /** Ids aller Alarme (auch ausgeschalteter) — für das Aufräumen des Alarm-Protokolls nach dem Löschen von Paaren. */
    @Query("SELECT id FROM alarms")
    suspend fun getAlarmIds(): List<Long>

    /** Ein Alarm (gespeicherter Stand beim Speichern aus dem Editor); null = gelöscht. */
    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarm(id: Long): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getAllEnabledAlarms(): List<AlarmEntity>

    /** Paare mit mindestens einem aktiven Alarm (nur die Alarm-Tabelle: Kurs-Updates lösen nichts aus). */
    @Query("SELECT DISTINCT watchId FROM alarms WHERE enabled = 1")
    fun observeWatchIdsWithEnabledAlarms(): Flow<List<Long>>

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
     * gleiche Bedingung, Schwelle, Fenster, Wiederholung und Alarm-Währung — `IS`, weil sie
     * null sein darf); sonst bleibt die Änderung des Nutzers. Rückgabe: geänderte Zeilen (0 = nicht gespeichert, nicht melden).
     */
    @Query(
        """
        UPDATE alarms
        SET lastTriggeredAt = :time, lastTriggeredPrice = :price,
            referencePrice = :referencePrice, referenceAt = :referenceAt, enabled = :enabled
        WHERE id = :id AND enabled = 1 AND `condition` = :condition AND threshold = :threshold
            AND windowHours = :windowHours AND repeating = :repeating AND currency IS :currency
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
        currency: String?,
    ): Int

    /** Neuer Bezug (Bewegungs-/Prozentalarm): Kurs und Beginn setzen — nur bei unverändertem Alarm (auch Währung). */
    @Query(
        "UPDATE alarms SET referencePrice = :price, referenceAt = :time WHERE id = :id AND enabled = 1 " +
            "AND `condition` = :condition AND threshold = :threshold AND windowHours = :windowHours " +
            "AND currency IS :currency"
    )
    suspend fun setAlarmReferenceIfUnchanged(
        id: Long, price: Double, time: Long, condition: String, threshold: Double, windowHours: Int, currency: String?,
    ): Int

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

    /**
     * Wie [rearmAlarm], aber nur bei unverändertem, eingeschaltetem Alarm (Aktualisierung) — auch gleiche Alarm-Währung
     * wie [markAlarmTriggered] (sonst stellte eine Kursmarke in EUR nach dem Wechsel auf USD scharf).
     */
    @Query(
        "UPDATE alarms SET referenceAt = 0 " +
            "WHERE id = :id AND enabled = 1 AND `condition` = :condition AND threshold = :threshold " +
            "AND windowHours = :windowHours AND currency IS :currency " +
            "AND `condition` IN ('NEAR_HIGH', 'NEAR_LOW', 'PRICE_ABOVE', 'PRICE_BELOW', " +
            "'FUNDING_ABOVE', 'FUNDING_BELOW', 'OI_UP', 'OI_DOWN')"
    )
    suspend fun rearmAlarmIfUnchanged(
        id: Long, condition: String, threshold: Double, windowHours: Int, currency: String?,
    ): Int

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
