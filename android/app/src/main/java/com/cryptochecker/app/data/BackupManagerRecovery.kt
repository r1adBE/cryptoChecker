// Zurückrollen eines abgebrochenen Wiederherstellens beim Start (Teil von `BackupManager`). Erweiterungsfunktion;
// Journal und Datenbanken bleiben in der Klasse. Gespiegelt in `BackupManager+Recovery.swift`.
package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupJournalCodec.previousFromJournal
import com.cryptochecker.app.data.BackupManager.Step
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Journal gefunden: zurückrollen, wenn [RestoreRecoveryPolicy] es noch erlaubt (genau ein weiterer Versuch nach einem
 * gescheiterten, nie mit einem Journal älter als einen Tag) — sonst aufgeben: Journal weg, Hinweis «nicht
 * zurückgeschrieben» einmal. Der Nutzer arbeitet nach einem gescheiterten Zurückrollen weiter; ein späteres
 * Zurückrollen überschriebe seine Änderungen.
 */
internal suspend fun BackupManager.recoverFromJournal(text: String) {
    val root = try {
        JSONObject(text)
    } catch (e: Exception) {
        null
    }
    val createdAt = RestoreRecoveryPolicy.createdAt(root?.optLong("createdAt", 0L) ?: 0L, journal.lastModified())
    val attemptedFor = StorageRecoveryNotice.rollBackAttemptedFor(context)
    if (RestoreRecoveryPolicy.decide(createdAt, attemptedFor, System.currentTimeMillis()) == RestoreRecoveryPolicy.Decision.GIVE_UP) {
        Timber.w("Wiederherstellen abgebrochen: Journal zu alt oder schon einmal versucht, kein Zurückrollen")
        // Vor dem Löschen vermerken: scheitert das Löschen, rollt auch der nächste Start nicht zurück
        StorageRecoveryNotice.recordRollBackAttempt(context, createdAt)
        runCatchingCancellable { journal.delete() }
        if (RestoreRecoveryPolicy.noticeOnGiveUp(createdAt, attemptedFor)) {
            StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = false)
        }
        return
    }
    // Eine Datenbank nur leer im Arbeitsspeicher: Zurückgeschriebenes ginge mit dem Lauf verloren, das Journal
    // wäre dann aber schon weg. Journal behalten, der nächste Start versucht es erneut (zählt nicht als Versuch;
    // Änderungen im Arbeitsspeicher gehen ohnehin mit dem Lauf verloren, das Alter begrenzt [RestoreRecoveryPolicy])
    openBoth()
    if (!rollBackTarget(AppDatabase.DB_NAME) || !rollBackTarget(PortfolioDatabase.DB_NAME)) {
        Timber.w("Wiederherstellen abgebrochen: Datenbank nur im Arbeitsspeicher, Zurückrollen beim nächsten Start")
        return
    }
    val previous = try {
        previousFromJournal(root ?: throw IllegalArgumentException("Kein JSON"))
    } catch (e: Exception) {
        Timber.w(e, "Wiederherstellen abgebrochen, Journal nicht lesbar")
        runCatchingCancellable { journal.delete() }
        StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = false)
        return
    }
    // Welche Schritte schon liefen, ist unbekannt: alle zurückschreiben (gleicher Stand, beliebig oft anwendbar)
    val complete = withContext(NonCancellable) {
        rollBack(previous, Step.entries.toSet(), IllegalStateException("Wiederherstellen abgebrochen"))
    }
    // Gescheitert: Journal bleibt für genau einen weiteren Versuch beim nächsten Start
    if (complete) runCatchingCancellable { journal.delete() } else StorageRecoveryNotice.recordRollBackAttempt(context, createdAt)
    StorageRecoveryNotice.recordInterruptedRestore(context, rolledBack = complete)
}
