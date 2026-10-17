package com.cryptochecker.app.data.portfolio

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {

    // Neueste zuerst — so zeigt die Detailansicht die Liste direkt richtig.
    @Query("SELECT * FROM portfolio_tx ORDER BY time DESC, id DESC")
    fun observeAll(): Flow<List<PortfolioTxEntity>>

    @Query("SELECT * FROM portfolio_tx ORDER BY time DESC, id DESC")
    suspend fun getAll(): List<PortfolioTxEntity>

    @Query("SELECT DISTINCT coin FROM portfolio_tx")
    suspend fun getCoins(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: PortfolioTxEntity): Long

    /**
     * Herkunftsschlüssel aller importierten Transaktionen (CSV-Import: Doppelte erkennen), samt dem zweiten Schlüssel
     * verknüpfter Umbuchungen (`linkedImportKey`) — beide Seiten gelten als importiert.
     */
    @Query(
        "SELECT importKey FROM portfolio_tx WHERE importKey IS NOT NULL " +
            "UNION ALL SELECT linkedImportKey FROM portfolio_tx WHERE linkedImportKey IS NOT NULL"
    )
    suspend fun getImportKeys(): List<String>

    /** Transaktionen eines CSV-Importlaufs (Liste «Importe», «Import rückgängig»). */
    @Query("SELECT * FROM portfolio_tx WHERE importBatch = :batch ORDER BY time DESC, id DESC")
    suspend fun getByImportBatch(batch: String): List<PortfolioTxEntity>

    /** Löscht genau die Transaktionen eines CSV-Importlaufs; Anzahl gelöscht. */
    @Query("DELETE FROM portfolio_tx WHERE importBatch = :batch")
    suspend fun deleteImportBatch(batch: String): Int

    /** Kennung des Importlaufs je importierter Transaktion (Liste der Importläufe, [com.cryptochecker.app.domain.portfolio.PortfolioImportBatch.summarize]). */
    @Query("SELECT importBatch FROM portfolio_tx WHERE importBatch IS NOT NULL")
    suspend fun getImportBatches(): List<String>

    @Update
    suspend fun update(tx: PortfolioTxEntity)

    @Query("DELETE FROM portfolio_tx WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM portfolio_tx")
    suspend fun deleteAll()
}
