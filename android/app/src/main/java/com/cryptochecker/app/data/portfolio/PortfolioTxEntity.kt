package com.cryptochecker.app.data.portfolio

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType

/**
 * Kauf oder Verkauf im Portfolio — unabhängig von der Merkliste.
 * Preise in USDT; [priceUsdt] null = unbekannt (z. B. übernommener Bestand).
 *
 * Spalten müssen exakt zu [com.cryptochecker.app.data.local.AppDatabase.MIGRATION_6_7] passen.
 */
@Entity(tableName = "portfolio_tx")
data class PortfolioTxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Basis-Symbol in Grossbuchstaben, z. B. «BTC». */
    @ColumnInfo val coin: String,

    @ColumnInfo val type: PortfolioTxType,

    /** Menge, immer > 0. */
    @ColumnInfo val amount: Double,

    @ColumnInfo val priceUsdt: Double? = null,

    /** Zeitpunkt des Handels (ms). */
    @ColumnInfo val time: Long,

    @ColumnInfo val note: String? = null,
) {
    fun toTrade(): PortfolioTrade = PortfolioTrade(
        id = id,
        coin = coin,
        type = type,
        amount = amount,
        priceUsdt = priceUsdt,
        time = time,
    )
}
