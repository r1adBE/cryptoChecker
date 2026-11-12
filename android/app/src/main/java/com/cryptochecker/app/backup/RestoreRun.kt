// Ablauf der Schreibschritte beim Wiederherstellen (Teil von `BackupManager.restore`): erst das Journal, dann die
// Schritte, bei einem Fehler zurückrollen. Reines Kotlin, mit Fälschungen testbar; Datenbanken bleiben in der Klasse.
// Gespiegelt in `BackupManager.restore` (iOS, `RestoreSteps`).
package com.cryptochecker.app.backup

import com.cryptochecker.app.backup.BackupManager.Step
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import com.cryptochecker.app.data.BackupJournalUnavailableException

internal object RestoreRun {

    /**
     * Ein Schreibschritt. Er gilt schon VOR dem Schreiben als berührt — auch ein Schritt in einer Transaktion (Merkliste,
     * Portfolio): Wird die Coroutine genau beim Commit abgebrochen, ist die Tabelle ersetzt, der Aufruf endet aber mit
     * einer `CancellationException`; ebenso kann nach dem Commit noch etwas scheitern. Einen unberührten Stand
     * zurückzuschreiben schadet nicht, einen ersetzten stehen zu lassen ergäbe den halben Stand (neue Merkliste, altes
     * Portfolio) ohne Journal.
     */
    class Write(val step: Step, val block: suspend () -> Unit)

    /** Journal des Wiederherstellens ([RestoreJournal]); in Tests eine Fälschung. */
    interface Journal {
        /** Bisherigen Stand versiegelt merken; false = kein Schlüssel. */
        fun write(): Boolean

        fun delete()
    }

    /**
     * Nie ohne Journal: Ohne gemerkten Stand rollte ein im Lauf beendeter Prozess nichts zurück (neue
     * Merkliste, altes Portfolio, keine Meldung) — dann lieber gar nichts schreiben ([BackupJournalUnavailableException]).
     *
     * Scheitert ein Schritt, schreibt [rollBack] den gemerkten Stand der berührten Teile zurück; nur wenn das ganz gelang
     * (true), ist das Journal weg — sonst bleibt es, und der nächste Start versucht es erneut.
     */
    suspend fun execute(
        journal: Journal,
        writes: List<Write>,
        rollBack: suspend (touched: Set<Step>, cause: Throwable) -> Boolean,
    ) {
        val journaled = try {
            journal.write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupJournalUnavailableException(e)
        }
        if (!journaled) throw BackupJournalUnavailableException(null)
        val touched = mutableSetOf<Step>()
        try {
            for (write in writes) {
                touched += write.step
                write.block()
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // Ganz zurückgerollt: Journal weg; sonst bleibt es, und der nächste Start versucht es erneut
                if (rollBack(touched, e)) runCatchingCancellable { journal.delete() }
                Unit
            }
            throw e
        }
        runCatchingCancellable { journal.delete() }.onFailure { Timber.w(it, "Wiederherstellen: Journal nicht gelöscht") }
    }
}

/**
 * Ablauf des Zurückrollens ([rollBack]): Einstellungen, Favoriten, Portfolio, zuletzt die Merkliste. Ein gescheiterter
 * Teil hält die übrigen nicht auf; sein Fehler hängt an der Ursache. Reines Kotlin, mit Fälschungen testbar.
 */
internal object RestoreRollback {

    /** Die vier Teile; die Datenbanken prüft der Aufrufer ([BackupManager.rollBackTarget]). */
    interface Parts {
        /** Darf ins Portfolio zurückgeschrieben werden (nicht nur leer im Arbeitsspeicher)? */
        fun mayWritePortfolio(): Boolean

        /** Dasselbe für die Merkliste (Hauptdatenbank). */
        fun mayWriteWatchlist(): Boolean

        suspend fun settings()

        suspend fun favorites()

        suspend fun portfolio()

        suspend fun watchlist()
    }

    /** @return true = alles Berührte zurückgeschrieben (das Journal darf weg). */
    suspend fun run(touched: Set<Step>, cause: Throwable, parts: Parts): Boolean {
        var complete = true
        suspend fun attempt(what: String, block: suspend () -> Unit) {
            runCatchingCancellable { block() }.onFailure {
                complete = false
                Timber.w(it, "Wiederherstellen: %s nicht zurückgesetzt", what)
                if (it !== cause) cause.addSuppressed(it)
            }
        }
        // Nie in eine Datenbank, die nur leer im Arbeitsspeicher liegt: unvollständig, das Journal bleibt
        fun target(ok: Boolean, what: String): Boolean = ok.also {
            if (!it) {
                complete = false
                Timber.w("Wiederherstellen: %s nur im Arbeitsspeicher, nicht zurückgesetzt", what)
            }
        }
        if (Step.SETTINGS in touched) attempt("Einstellungen") { parts.settings() }
        if (Step.FAVORITES in touched) attempt("Favoriten") { parts.favorites() }
        if (Step.PORTFOLIO in touched && target(parts.mayWritePortfolio(), "Portfolio")) attempt("Portfolio") { parts.portfolio() }
        if (Step.WATCHLIST in touched && target(parts.mayWriteWatchlist(), "Merkliste")) attempt("Merkliste") { parts.watchlist() }
        Timber.w(cause, "Wiederherstellen fehlgeschlagen, bisheriger Stand zurückgeschrieben")
        return complete
    }
}
