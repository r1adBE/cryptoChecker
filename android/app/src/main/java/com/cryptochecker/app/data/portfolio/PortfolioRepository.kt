package com.cryptochecker.app.data.portfolio

import androidx.core.content.edit
import android.content.Context
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Zugriff auf die Portfolio-Transaktionen. */
@Singleton
class PortfolioRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val portfolioDao: PortfolioDao,
    private val watchDao: WatchDao,
) {
    private val migrationMutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun observeTransactions(): Flow<List<PortfolioTxEntity>> = portfolioDao.observeAll()

    suspend fun getTransactions(): List<PortfolioTxEntity> = portfolioDao.getAll()

    /** Neu anlegen (id 0) oder ändern. Coin wird vereinheitlicht (Grossbuchstaben). */
    suspend fun save(tx: PortfolioTxEntity): Long {
        val clean = tx.copy(
            coin = PortfolioCalculator.normalizeCoin(tx.coin),
            note = tx.note?.trim()?.takeIf { it.isNotEmpty() },
        )
        require(clean.coin.isNotEmpty() && clean.amount > 0.0) { "Ungültige Transaktion" }
        return if (clean.id == 0L) {
            portfolioDao.insert(clean)
        } else {
            portfolioDao.update(clean)
            clean.id
        }
    }

    suspend fun delete(id: Long) = portfolioDao.delete(id)

    /** Löscht alle Transaktionen von [coin] und gibt sie zurück (für «Rückgängig»). */
    suspend fun deleteCoin(coin: String): List<PortfolioTxEntity> = database.withTransaction {
        val key = PortfolioCalculator.normalizeCoin(coin)
        val removed = portfolioDao.getAll().filter { PortfolioCalculator.normalizeCoin(it.coin) == key }
        removed.forEach { portfolioDao.delete(it.id) }
        removed
    }

    /** Legt gelöschte Transaktionen mit ihren alten ids wieder an («Rückgängig»). */
    suspend fun restore(transactions: List<PortfolioTxEntity>) {
        if (transactions.isEmpty()) return
        database.withTransaction { transactions.forEach { portfolioDao.insert(it) } }
    }

    /** «Portfolio leeren»: alle Transaktionen löschen. */
    suspend fun clearAll() = portfolioDao.deleteAll()

    /** Ersetzt alle Transaktionen (Wiederherstellen einer Sicherung). */
    suspend fun replaceAll(transactions: List<PortfolioTxEntity>) {
        database.withTransaction {
            portfolioDao.deleteAll()
            transactions.forEach { portfolioDao.insert(it.copy(coin = PortfolioCalculator.normalizeCoin(it.coin))) }
        }
    }

    /**
     * Einmalige Übernahme des alten Bestands je Paar (watches.holdings) ins
     * Portfolio: je Coin ein Kauf ohne Preis (Mengen gleicher Coins addiert),
     * danach wird der Bestand in der Merkliste geleert. Läuft nur einmal
     * (Merker in den SharedPreferences), in einer Transaktion.
     */
    suspend fun migrateHoldingsOnce() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        migrationMutex.withLock {
            if (prefs.getBoolean(KEY_MIGRATED, false)) return
            runCatching {
                val count = database.withTransaction { importHoldings(onlyNewCoins = false) }
                if (count > 0) Timber.i("Portfolio: %d Bestände aus der Merkliste übernommen", count)
                prefs.edit { putBoolean(KEY_MIGRATED, true) }
            }.onFailure { Timber.w(it, "Portfolio: Übernahme des Bestands fehlgeschlagen") }
        }
    }

    /**
     * Wandelt Bestände der Merkliste in Käufe ohne Preis um und leert sie.
     * Mit [onlyNewCoins] nur für Coins, die noch keine Transaktion haben
     * (Wiederherstellen einer alten Sicherung — sonst doppelt).
     * Muss innerhalb einer Datenbank-Transaktion aufgerufen werden.
     * @return Anzahl angelegter Käufe.
     */
    suspend fun importHoldings(onlyNewCoins: Boolean): Int {
        val amounts = watchDao.getWatches()
            .mapNotNull { w ->
                val amount = w.holdings?.takeIf { it > 0.0 && !it.isInfinite() } ?: return@mapNotNull null
                PortfolioCalculator.normalizeCoin(w.baseAsset).takeIf { it.isNotEmpty() }?.let { it to amount }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sum() }
        if (amounts.isEmpty()) return 0

        val existing = if (onlyNewCoins) {
            portfolioDao.getCoins().map { PortfolioCalculator.normalizeCoin(it) }.toSet()
        } else emptySet()
        val now = System.currentTimeMillis()
        var created = 0
        amounts.forEach { (coin, amount) ->
            if (coin in existing) return@forEach
            portfolioDao.insert(
                PortfolioTxEntity(
                    coin = coin,
                    type = PortfolioTxType.BUY,
                    amount = amount,
                    priceUsdt = null,
                    time = now,
                )
            )
            created++
        }
        watchDao.clearAllHoldings()
        return created
    }

    private companion object {
        const val PREFS = "portfolio"
        const val KEY_MIGRATED = "holdings_migrated"
    }
}
