package com.cryptochecker.app.data.portfolio

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.cryptochecker.app.domain.alarm.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Zeile der Tabelle «portfolio_alarms» (Room); Fachmodell und Bedeutung: [PortfolioAlarmEntity] in :domain. Spalten, Typen
 * und NOT NULL bilden das Schema der [PortfolioDatabase]; Änderungen nur mit Migration (Room prüft das Schema beim Öffnen).
 * Ohne Standardwerte, damit [toRow] kein Feld vergessen kann.
 */
@Entity(tableName = "portfolio_alarms")
data class PortfolioAlarmRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    @ColumnInfo val kind: PortfolioAlarmKind,
    @ColumnInfo val threshold: Double,
    @ColumnInfo val currency: String?,
    @ColumnInfo val enabled: Boolean,
    @ColumnInfo val repeating: Boolean,
    @ColumnInfo val referenceAt: Long,
    @ColumnInfo val lastTriggeredAt: Long,
    @ColumnInfo val lastTriggeredValue: Double?,
)

/** Tabellenzeile → Fachmodell, alle Felder (PortfolioRowMappingTest prüft Vollständigkeit und Hin- und Rückweg). */
fun PortfolioAlarmRow.toEntity(): PortfolioAlarmEntity = PortfolioAlarmEntity(
    id = id,
    kind = kind,
    threshold = threshold,
    currency = currency,
    enabled = enabled,
    repeating = repeating,
    referenceAt = referenceAt,
    lastTriggeredAt = lastTriggeredAt,
    lastTriggeredValue = lastTriggeredValue,
)

/** Fachmodell → Tabellenzeile, alle Felder. */
fun PortfolioAlarmEntity.toRow(): PortfolioAlarmRow = PortfolioAlarmRow(
    id = id,
    kind = kind,
    threshold = threshold,
    currency = currency,
    enabled = enabled,
    repeating = repeating,
    referenceAt = referenceAt,
    lastTriggeredAt = lastTriggeredAt,
    lastTriggeredValue = lastTriggeredValue,
)

/** Portfolio-Alarme. Room liest und schreibt [PortfolioAlarmRow]; nach aussen gehen nur [PortfolioAlarmEntity]. */
@Dao
interface PortfolioAlarmDao {

    @Query("SELECT * FROM portfolio_alarms ORDER BY id")
    fun observeAllRows(): Flow<List<PortfolioAlarmRow>>

    fun observeAll(): Flow<List<PortfolioAlarmEntity>> = observeAllRows().map { rows -> rows.map { it.toEntity() } }

    @Query("SELECT * FROM portfolio_alarms ORDER BY id")
    suspend fun getAllRows(): List<PortfolioAlarmRow>

    suspend fun getAll(): List<PortfolioAlarmEntity> = getAllRows().map { it.toEntity() }

    @Query("SELECT COUNT(*) FROM portfolio_alarms WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRow(alarm: PortfolioAlarmRow): Long

    suspend fun insert(alarm: PortfolioAlarmEntity): Long = insertRow(alarm.toRow())

    @Query("DELETE FROM portfolio_alarms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM portfolio_alarms")
    suspend fun deleteAll()

    /** Ein-/Ausschalten; eingeschaltet wieder scharf (wie die Kursmarken der Paar-Alarme). */
    @Query("UPDATE portfolio_alarms SET enabled = :enabled, referenceAt = CASE WHEN :enabled THEN 0 ELSE referenceAt END WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE portfolio_alarms SET referenceAt = 0 WHERE id = :id")
    suspend fun rearm(id: Long)

    /**
     * Ausgelösten Alarm speichern — nur, wenn er seit dem Lesen unverändert ist (eingeschaltet,
     * gleicher Bezug, gleiche Art, Schwelle, Wiederholung und Währung — `IS`, weil sie null sein
     * darf); sonst bleibt die Änderung des Nutzers (z. B. gerade ausgeschaltet) — wie
     * WatchDao.markAlarmTriggered. Rückgabe: geänderte Zeilen (0 = nicht gespeichert, nicht melden).
     */
    @Query(
        "UPDATE portfolio_alarms SET referenceAt = :time, lastTriggeredAt = :time, " +
            "lastTriggeredValue = :value, enabled = :enabled " +
            "WHERE id = :id AND enabled = 1 AND referenceAt = :expectedReferenceAt AND kind = :kind " +
            "AND threshold = :threshold AND repeating = :repeating AND currency IS :currency"
    )
    suspend fun markTriggered(
        id: Long,
        time: Long,
        value: Double,
        enabled: Boolean,
        expectedReferenceAt: Long,
        /** Name von [PortfolioAlarmKind] (so gespeichert). */
        kind: String,
        threshold: Double,
        repeating: Boolean,
        currency: String?,
    ): Int
}
