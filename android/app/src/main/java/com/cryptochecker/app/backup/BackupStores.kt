package com.cryptochecker.app.backup

import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioDao
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.WatchedAddressDao
import javax.inject.Inject

/**
 * Was [BackupManager] unmittelbar liest und schreibt: die beiden Datenbanken (je eine Transaktion pro Datei) und ihre
 * DAOs. Ohne eigenen Zustand — die Datenbanken und DAOs sind selbst `@Singleton`.
 */
class BackupStores @Inject constructor(
    val database: AppDatabase,
    val portfolioDatabase: PortfolioDatabase,
    val watchDao: WatchDao,
    val portfolioDao: PortfolioDao,
    val portfolioAlarmDao: PortfolioAlarmDao,
    val watchedAddressDao: WatchedAddressDao,
)
