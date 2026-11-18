package com.cryptochecker.app.domain.portfolio

/** Portfolio lesen: Transaktionen und beobachtete Adressen (Umsetzung: `PortfolioRepository`). */
interface PortfolioReader {
    suspend fun getTransactions(): List<PortfolioTxEntity>

    suspend fun getWatchedAddresses(): List<WatchedAddress>
}
