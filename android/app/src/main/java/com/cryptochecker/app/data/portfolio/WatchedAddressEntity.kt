package com.cryptochecker.app.data.portfolio

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import kotlinx.coroutines.flow.Flow

/**
 * Beobachtete öffentliche Adresse (nur lesen) in der PORTFOLIO-Datenbank — gehört zu den
 * Portfolio-Daten (gleiche Regel für die Systemsicherung, [PortfolioBackupMirror]).
 *
 * Registriert in [PortfolioDatabase] (v3, angelegt in [PortfolioDatabase.MIGRATION_2_3]; `costLots` seit v6).
 * Spalten, Typen und NOT NULL müssen exakt zu [CREATE_SQL] passen (Room prüft beim Öffnen).
 * [chain] als Text (Name von [AddressChain]) — so braucht die Datenbank keinen neuen TypeConverter.
 */
@Entity(
    tableName = WatchedAddressEntity.TABLE,
    indices = [Index(value = ["chain", "address"], unique = true)],
)
data class WatchedAddressEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Name von [AddressChain] («BTC», «ETH», «SOL» …). */
    @ColumnInfo val chain: String,
    /** Normalisiert (AddressValidation): Bech32 klein, Ethereum EIP-55; oder xpub/ypub/zpub. */
    @ColumnInfo val address: String,
    @ColumnInfo val label: String? = null,
    /** Exakter Text («0.00012345»); null = noch nie geladen. */
    @ColumnInfo val lastBalance: String? = null,
    @ColumnInfo val lastUpdated: Long = 0,
    @ColumnInfo val lastAttempt: Long = 0,
    @ColumnInfo val lastError: String? = null,
    @ColumnInfo val lastProvider: String? = null,
    @ColumnInfo val addedAt: Long = 0,
    /**
     * Beobachtete Guthaben als JSON (`[{"t":…,"b":"…"}]`); null = noch keine. Mit Token-Guthaben, «Tokens mitzählen»
     * aus oder xpub-Zwischenspeicher ein Objekt ([WatchedAddressExtras]) — ohne neue Spalte.
     * Eigene Spalte statt Tabelle: geht mit der Adresse weg, je Tag höchstens ein Eintrag.
     */
    @ColumnInfo val observations: String? = null,
    /**
     * Eröffnungsposten als JSON ([AddressCostLots.encode]); null = keine. Seit v6 ([PortfolioDatabase.MIGRATION_5_6]):
     * eigene Spalte, damit ein Abruf (schreibt `observations`) nie die Posten überschreibt.
     */
    @ColumnInfo val costLots: String? = null,
) {
    companion object {
        const val TABLE = "watched_addresses"

        /**
         * Tabelle wie in v3 ([PortfolioDatabase.MIGRATION_2_3]) — ohne `costLots`, die erst [PortfolioDatabase.MIGRATION_5_6]
         * anfügt (sonst scheiterte die Kette 2 → 6 an einer doppelten Spalte).
         */
        const val CREATE_SQL_V3 = "CREATE TABLE IF NOT EXISTS `watched_addresses` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chain` TEXT NOT NULL, `address` TEXT NOT NULL, " +
            "`label` TEXT, `lastBalance` TEXT, `lastUpdated` INTEGER NOT NULL, `lastAttempt` INTEGER NOT NULL, " +
            "`lastError` TEXT, `lastProvider` TEXT, `addedAt` INTEGER NOT NULL, `observations` TEXT)"

        /** Aktuelle Tabelle (Spalten exakt wie die Entity, v6). */
        const val CREATE_SQL = "CREATE TABLE IF NOT EXISTS `watched_addresses` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chain` TEXT NOT NULL, `address` TEXT NOT NULL, " +
            "`label` TEXT, `lastBalance` TEXT, `lastUpdated` INTEGER NOT NULL, `lastAttempt` INTEGER NOT NULL, " +
            "`lastError` TEXT, `lastProvider` TEXT, `addedAt` INTEGER NOT NULL, `observations` TEXT, `costLots` TEXT)"

        /** Name nach Room-Konvention (`index_<Tabelle>_<Spalten>`). */
        const val INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_watched_addresses_chain_address` ON `watched_addresses` (`chain`, `address`)"

        fun from(a: WatchedAddress) = WatchedAddressEntity(
            id = a.id,
            chain = a.chain.name,
            address = a.address,
            label = a.label,
            lastBalance = a.lastBalance,
            lastUpdated = a.lastUpdated,
            lastAttempt = a.lastAttempt,
            lastError = a.lastError,
            lastProvider = a.lastProvider,
            addedAt = a.addedAt,
            observations = WatchedAddressExtras.encode(a),
            costLots = AddressCostLots.encode(AddressCostLots.clean(a.costLots)),
        )
    }
}

/** Unbekanntes Netz (aus einer neueren Version) = null, wird übersprungen. */
fun WatchedAddressEntity.toDomain(): WatchedAddress? {
    val c = AddressChain.fromName(chain) ?: return null
    val extras = WatchedAddressExtras.decode(observations, c)
    return WatchedAddress(
        id, c, address, label, lastBalance, lastUpdated, lastAttempt, lastError, lastProvider, addedAt,
        extras.observations,
        tokenBalances = extras.tokenBalances,
        tokenObservations = extras.tokenObservations,
        countTokens = extras.countTokens,
        xpub = extras.xpub,
        networks = extras.networks,
        countInPortfolio = extras.countInPortfolio,
        unknownTokens = extras.unknownTokens,
        overlapChecked = extras.overlapChecked,
        overlapLegacy = extras.overlapLegacy,
        costLots = AddressCostLots.decode(costLots),
    )
}

@Dao
interface WatchedAddressDao {

    @Query("SELECT * FROM watched_addresses ORDER BY addedAt, id")
    fun observeAll(): Flow<List<WatchedAddressEntity>>

    @Query("SELECT * FROM watched_addresses ORDER BY addedAt, id")
    suspend fun getAll(): List<WatchedAddressEntity>

    @Query("SELECT * FROM watched_addresses WHERE id = :id")
    suspend fun getById(id: Long): WatchedAddressEntity?

    @Query("SELECT COUNT(*) FROM watched_addresses")
    suspend fun count(): Int

    /** Doppelte Adresse (gleiches Netz): nichts eingefügt, Rückgabe -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(address: WatchedAddressEntity): Long

    @Query("DELETE FROM watched_addresses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM watched_addresses")
    suspend fun deleteAll()

    @Query("UPDATE watched_addresses SET label = :label WHERE id = :id")
    suspend fun setLabel(id: Long, label: String?)

    /** Erfolgreicher Abruf: Guthaben, Zeit, Anbieter; Fehler gelöscht. */
    @Query(
        "UPDATE watched_addresses SET lastBalance = :balance, lastUpdated = :time, lastAttempt = :time, " +
            "lastError = NULL, lastProvider = :provider WHERE id = :id"
    )
    suspend fun saveBalance(id: Long, balance: String, time: Long, provider: String?)

    @Query("SELECT observations FROM watched_addresses WHERE id = :id")
    suspend fun getObservations(id: Long): String?

    /** Beobachtete Guthaben ersetzen ([AddressBalanceHistory.append] im Aufrufer). */
    @Query("UPDATE watched_addresses SET observations = :observations WHERE id = :id")
    suspend fun setObservations(id: Long, observations: String?)

    /** Erfolgreicher Abruf samt Zusatzdaten ([WatchedAddressExtras.encode] im Aufrufer). */
    @Query(
        "UPDATE watched_addresses SET lastBalance = :balance, lastUpdated = :time, lastAttempt = :time, " +
            "lastError = NULL, lastProvider = :provider, observations = :observations WHERE id = :id"
    )
    suspend fun saveFetched(id: Long, balance: String, time: Long, provider: String?, observations: String?)

    /** Zusatzdaten und letzten Versuch ersetzen («Tokens mitzählen»). */
    @Query("UPDATE watched_addresses SET observations = :observations, lastAttempt = :lastAttempt WHERE id = :id")
    suspend fun setExtras(id: Long, observations: String?, lastAttempt: Long)

    /** Eröffnungsposten ersetzen ([AddressCostLots.encode] im Aufrufer); Guthaben und Zusatzdaten bleiben. */
    @Query("UPDATE watched_addresses SET costLots = :costLots WHERE id = :id")
    suspend fun setCostLots(id: Long, costLots: String?)

    /** Fehlgeschlagener Abruf: letztes Guthaben bleibt stehen. */
    @Query("UPDATE watched_addresses SET lastAttempt = :time, lastError = :error WHERE id = :id")
    suspend fun saveError(id: Long, time: Long, error: String)
}
