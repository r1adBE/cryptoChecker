package com.cryptochecker.app.data.portfolio

/**
 * Portfolio in diesem Lauf nur leer im Arbeitsspeicher, obwohl die verschlüsselte Datei mit gespeichertem Stand da ist
 * ([com.cryptochecker.app.data.local.DatabaseEncryptionNotice.storedKept]): Was jetzt geschrieben wird, ist nach dem
 * Prozess-Ende weg, und der gespeicherte Stand kommt nach einem Neustart zurück. Neue bzw. geänderte Transaktionen und der
 * CSV-Import werden darum gar nicht erst geschrieben ([PortfolioRepository], Hinweis statt stillem Verlust); Einspielen
 * einer Sicherung lehnt BackupManager schon ab (BackupStorageKeptException). Wie [com.cryptochecker.app.data.local.WatchlistStorageLock].
 *
 * Nicht gesperrt: Portfolio nur im Arbeitsspeicher ohne gespeicherten Stand (kein Schlüssel, nie unverschlüsselt angelegt) —
 * dort gibt es nichts zu verlieren, der Dauerhinweis im Portfolio sagt, dass nichts gespeichert wird.
 */
object PortfolioStorageLock {

    /** Ist das Portfolio ([PortfolioDatabase.DB_NAME]) unter den nur im Arbeitsspeicher gehaltenen Dateien [keptDatabases]? */
    fun isLocked(keptDatabases: Set<String>): Boolean = PortfolioDatabase.DB_NAME in keptDatabases

    /** Transaktionen speichern bzw. importieren sperren? */
    fun blocksWrites(keptDatabases: Set<String>): Boolean = isLocked(keptDatabases)
}

/** Schreiben ins Portfolio abgelehnt: gespeicherter Stand gerade nicht lesbar ([PortfolioStorageLock]). */
class PortfolioStorageLockedException : IllegalStateException("Portfolio-Stand gerade nicht lesbar, nichts geschrieben")
