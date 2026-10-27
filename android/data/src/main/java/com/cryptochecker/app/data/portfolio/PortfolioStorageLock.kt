package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.local.DatabaseEncryptionNotice

/**
 * Portfolio in diesem Lauf nur leer im Arbeitsspeicher, obwohl die verschlüsselte Datei mit gespeichertem Stand da ist
 * ([com.cryptochecker.app.data.local.DatabaseEncryptionNotice.storedKept]): Was jetzt geschrieben wird, ist nach dem
 * Prozess-Ende weg, und der gespeicherte Stand kommt nach einem Neustart zurück. Neue bzw. geänderte Transaktionen, der
 * CSV-Import, Löschen, «Rückgängig» und Ersetzen werden darum gar nicht erst geschrieben ([PortfolioRepository], Hinweis
 * statt stillem Verlust), ebenso beobachtete Adressen (hinzufügen, ändern, entfernen, Einstandsposten); Abrufe der
 * Adress-Guthaben und automatische Umstellungen laufen weiter. Einspielen einer Sicherung lehnt BackupManager schon ab
 * (BackupStorageKeptException). Wie [com.cryptochecker.app.data.local.WatchlistStorageLock]; iOS: `PortfolioStorageLock`.
 *
 * Nicht gesperrt: Portfolio nur im Arbeitsspeicher ohne gespeicherten Stand (kein Schlüssel, nie unverschlüsselt angelegt) —
 * dort gibt es nichts zu verlieren, der Dauerhinweis im Portfolio sagt, dass nichts gespeichert wird.
 */
object PortfolioStorageLock {

    /** Ist das Portfolio ([PortfolioDatabase.DB_NAME]) unter den nur im Arbeitsspeicher gehaltenen Dateien [keptDatabases]? */
    fun isLocked(keptDatabases: Set<String>): Boolean = PortfolioDatabase.DB_NAME in keptDatabases

    /** Schreibwege des Nutzers (speichern, importieren, löschen, «Rückgängig», ersetzen) sperren? */
    fun blocksWrites(keptDatabases: Set<String>): Boolean = isLocked(keptDatabases)
}

/** Schreiben ins Portfolio abgelehnt: gespeicherter Stand gerade nicht lesbar ([PortfolioStorageLock]). */
class PortfolioStorageLockedException : IllegalStateException("Portfolio-Stand gerade nicht lesbar, nichts geschrieben")

/**
 * Schreibweg des Nutzers ablehnen, solange gesperrt ([PortfolioStorageLock.blocksWrites]). Erst nach dem Öffnen der
 * Portfolio-Datenbank verlässlich — darum innerhalb ihrer Transaktion aufrufen.
 * @throws PortfolioStorageLockedException gesperrt
 */
fun ensurePortfolioWritable(keptDatabases: Set<String> = DatabaseEncryptionNotice.storedKept.value) {
    if (PortfolioStorageLock.blocksWrites(keptDatabases)) throw PortfolioStorageLockedException()
}
