package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.domain.portfolio.PortfolioStorageLock
import com.cryptochecker.app.domain.portfolio.PortfolioStorageLockedException

/**
 * Schreibweg des Nutzers ablehnen, solange gesperrt ([PortfolioStorageLock.blocksWrites]). Erst nach dem Öffnen der
 * Portfolio-Datenbank verlässlich — darum innerhalb ihrer Transaktion aufrufen.
 * @throws PortfolioStorageLockedException gesperrt
 */
fun ensurePortfolioWritable(keptDatabases: Set<String> = DatabaseEncryptionNotice.storedKept.value) {
    if (PortfolioStorageLock.blocksWrites(keptDatabases)) throw PortfolioStorageLockedException()
}
