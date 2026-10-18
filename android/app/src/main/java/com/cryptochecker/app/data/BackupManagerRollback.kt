// Zurückschreiben des gemerkten Stands nach einem gescheiterten Wiederherstellen (Teil von `BackupManager`) und das
// Ersetzen der Merkliste. Erweiterungsfunktionen; Datenbanken und Sperren bleiben in der Klasse.
package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupJournalCodec.PreviousState
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import timber.log.Timber
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
 * an [cause] angehängt). Portfolio-Zeilen und Alarme kommen unverändert zurück (samt Alarm-Zustand).
 * @return true = alles zurückgeschrieben (das Journal darf weg).
 */
internal suspend fun BackupManager.rollBack(previous: PreviousState, touched: Set<Step>, cause: Throwable): Boolean {
    var complete = true
    suspend fun attempt(what: String, block: suspend () -> Unit) {
        runCatchingCancellable { block() }.onFailure {
            complete = false
            Timber.w(it, "Wiederherstellen: %s nicht zurückgesetzt", what)
            if (it !== cause) cause.addSuppressed(it)
        }
    }
    // Nie in eine Datenbank, die nur leer im Arbeitsspeicher liegt: unvollständig, das Journal bleibt
    openBoth()
    fun target(name: String, what: String): Boolean = rollBackTarget(name).also { ok ->
        if (!ok) {
            complete = false
            Timber.w("Wiederherstellen: %s nur im Arbeitsspeicher, nicht zurückgesetzt", what)
        }
    }
    if (Step.SETTINGS in touched) attempt("Einstellungen") { settingsCodec.restoreSettings(previous.settings, trusted = true) }
    if (Step.FAVORITES in touched) attempt("Favoriten") {
        previous.favorites.forEach { (kind, items) -> favoritesRepository.setAll(kind, items) }
    }
    if (Step.PORTFOLIO in touched && target(PortfolioDatabase.DB_NAME, "Portfolio")) attempt("Portfolio") {
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
    // Auch nach dem Portfolio-Schritt: importHoldings leert den Bestand der Merkliste
    if (Step.WATCHLIST in touched && target(AppDatabase.DB_NAME, "Merkliste")) {
        attempt("Merkliste") { writeWatchlist(previous.watches, previous.alarms) }
    }
    Timber.w(cause, "Wiederherstellen fehlgeschlagen, bisheriger Stand zurückgeschrieben")
    return complete
}
