package com.cryptochecker.app.data.portfolio

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.cryptochecker.app.domain.alarm.PortfolioAlarmInput
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import kotlinx.coroutines.flow.Flow

/**
 * Alarm «Portfolio-Wert» (Runde 28): Gesamtwert über/unter einem Betrag oder Veränderung
 * «heute» ±x %. Eigene Tabelle `portfolio_alarms` (MIGRATION_10_11) statt der Tabelle
 * `alarms`: Die gehört über einen Fremdschlüssel zu einem Paar der Merkliste — ein
 * Portfolio-Alarm hat keins, ein Schein-Paar wäre in Merkliste, Sicherung und Widgets sichtbar.
 * Spalten, Typen und NOT NULL exakt wie in MIGRATION_10_11 (Room prüft das Schema beim Öffnen).
 * Regeln: [com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic].
 */
@Entity(tableName = "portfolio_alarms")
data class PortfolioAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Name von [PortfolioAlarmKind]. */
    @ColumnInfo val kind: PortfolioAlarmKind,
    /** Betrag (VALUE_*) in [currency] oder Prozent (CHANGE_*). */
    @ColumnInfo val threshold: Double,
    /** Währung des Betrags (Umrechnungswährung beim Anlegen); bei Prozent ohne Bedeutung. */
    @ColumnInfo val currency: String? = null,
    @ColumnInfo val enabled: Boolean = true,
    /** false = schaltet sich nach dem Auslösen ab. */
    @ColumnInfo val repeating: Boolean = false,
    /** 0 = scharf, > 0 = gemeldet (Zeitpunkt), bis der Wert hinter die Marke zurückkehrt. */
    @ColumnInfo val referenceAt: Long = 0,
    @ColumnInfo val lastTriggeredAt: Long = 0,
    /** Gemessener Wert beim letzten Auslösen (Betrag bzw. Prozent). */
    @ColumnInfo val lastTriggeredValue: Double? = null,
)

fun PortfolioAlarmEntity.toInput() = PortfolioAlarmInput(
    kind = kind,
    threshold = threshold,
    currency = currency,
    enabled = enabled,
    referenceAt = referenceAt,
    lastTriggeredAt = lastTriggeredAt,
)

@Dao
interface PortfolioAlarmDao {

    @Query("SELECT * FROM portfolio_alarms ORDER BY id")
    fun observeAll(): Flow<List<PortfolioAlarmEntity>>

    @Query("SELECT * FROM portfolio_alarms ORDER BY id")
    suspend fun getAll(): List<PortfolioAlarmEntity>

    @Query("SELECT COUNT(*) FROM portfolio_alarms WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: PortfolioAlarmEntity): Long

    @Query("DELETE FROM portfolio_alarms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM portfolio_alarms")
    suspend fun deleteAll()

    /** Ein-/Ausschalten; eingeschaltet wieder scharf (wie die Kursmarken der Paar-Alarme). */
    @Query("UPDATE portfolio_alarms SET enabled = :enabled, referenceAt = CASE WHEN :enabled THEN 0 ELSE referenceAt END WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE portfolio_alarms SET referenceAt = 0 WHERE id = :id")
    suspend fun rearm(id: Long)

    @Query(
        "UPDATE portfolio_alarms SET referenceAt = :time, lastTriggeredAt = :time, " +
            "lastTriggeredValue = :value, enabled = :enabled WHERE id = :id"
    )
    suspend fun markTriggered(id: Long, time: Long, value: Double, enabled: Boolean)
}
