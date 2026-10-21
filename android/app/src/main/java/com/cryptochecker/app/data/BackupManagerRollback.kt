// Zurückschreiben des gemerkten Stands nach einem gescheiterten Wiederherstellen (Teil von `BackupManager`) und das
// Ersetzen der Merkliste. Erweiterungsfunktionen; Datenbanken und Sperren bleiben in der Klasse.
package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupJournalCodec.PreviousState
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.BackupManager.Step

/** Merkliste in einer Transaktion ersetzen; ein übergangenes Paar bricht ab (statt Alarme zu verlieren). */
internal suspend fun BackupManager.writeWatchlist(watches: List<WatchEntity>, alarms: List<AlarmEntity>) {
    // Unter der Refresh-Sperre: Ein laufender Durchlauf schrieb sonst Kurse alter Paare unter Ids, die
    // die Sicherung neu vergibt (zusätzlich verwirft WatchDao.updatePriceForPair Kurse anderer Paare).
    // Gepufferte Live-Kurse gehören zu den alten Paaren: vorher vergessen.
    priceRefresher.withRefreshLock {
        livePriceStream.forgetAll()
        database.withTransaction {
            watchDao.deleteAllWatches()          // Alarme fallen per Fremdschlüssel mit weg
            watches.forEach { w -> check(watchDao.insertWatch(w) != -1L) { "Paar ${w.id} nicht übernommen" } }
            alarms.forEach { watchDao.insertAlarm(it) }
        }
    }
}

/**
 * Schreibt den gemerkten Stand der schon berührten Teile zurück (bestmöglich; Fehler dabei werden
 * an [cause] angehängt, Ablauf in [RestoreRollback]). Portfolio-Zeilen und Alarme kommen unverändert zurück (samt Alarm-Zustand).
 * @return true = alles zurückgeschrieben (das Journal darf weg).
 */
internal suspend fun BackupManager.rollBack(previous: PreviousState, touched: Set<Step>, cause: Throwable): Boolean {
    // Nie in eine Datenbank, die nur leer im Arbeitsspeicher liegt: unvollständig, das Journal bleibt
    openBoth()
    return RestoreRollback.run(touched, cause, object : RestoreRollback.Parts {
        override fun mayWritePortfolio() = rollBackTarget(PortfolioDatabase.DB_NAME)
        override fun mayWriteWatchlist() = rollBackTarget(AppDatabase.DB_NAME)
        override suspend fun settings() {
            settingsCodec.restoreSettings(previous.settings, trusted = true)
        }
        override suspend fun favorites() {
            previous.favorites.forEach { (kind, items) -> favoritesRepository.setAll(kind, items) }
        }
        override suspend fun portfolio() {
            portfolioDatabase.withTransaction {
                portfolioDao.deleteAll()
                previous.portfolio.forEach { portfolioDao.insert(it) }
                portfolioAlarmDao.deleteAll()
                previous.portfolioAlarms.forEach { portfolioAlarmDao.insert(it) }
                // Samt letztem Guthaben (unverändert zurück)
                watchedAddressDao.deleteAll()
                previous.watchedAddresses.forEach { watchedAddressDao.insert(it) }
            }
        }
        override suspend fun watchlist() = writeWatchlist(previous.watches, previous.alarms)
    })
}
