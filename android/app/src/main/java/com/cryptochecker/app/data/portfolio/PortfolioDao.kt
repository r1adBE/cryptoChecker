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

    @Update
    suspend fun update(tx: PortfolioTxEntity)

    @Query("DELETE FROM portfolio_tx WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM portfolio_tx")
    suspend fun deleteAll()
}
