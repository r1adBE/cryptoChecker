package com.cryptochecker.app.data.local

/**
 * Merkliste in diesem Lauf nur leer im Arbeitsspeicher, obwohl die verschlüsselte Datei mit gespeichertem Stand da ist
 * ([DatabaseEncryptionNotice.storedKept]): Was jetzt angelegt wird, ist nach dem Prozess-Ende weg. Seit Build 103
 * (Review F-08) ist die Merkliste darum nur lesbar wie das Portfolio ([com.cryptochecker.app.data.portfolio.PortfolioStorageLock]):
 * Alle Schreibwege des Nutzers (Paare hinzufügen, bearbeiten, löschen, Notiz, Gruppe, Alarme …) lehnt
 * [com.cryptochecker.app.data.WatchRepository] zentral ab und meldet es kurz ([WatchlistWriteRefusal]); der
 * Speicher-Hinweis der Merkliste bleibt stehen (nach jedem anderen Banner kommt er wieder). Hintergrundarbeit (Kurse,
 * Alarm-Zustand) schreibt weiter in die leere Datenbank im Arbeitsspeicher — dort ist nichts, was verloren ginge.
 * Gleich wie iOS (`WatchlistStorageLock`, `AppData.watchlistWriteRefused`).
 *
 * Nicht gesperrt: nur im Arbeitsspeicher ohne gespeicherten Stand ([Notice.NOT_SAVED]) — dort gibt es nichts zu verlieren.
 */
object WatchlistStorageLock {

    /** Ist die Merkliste ([AppDatabase.DB_NAME]) unter den nur im Arbeitsspeicher gehaltenen Dateien [keptDatabases]? */
    fun isLocked(keptDatabases: Set<String>): Boolean = AppDatabase.DB_NAME in keptDatabases

    /** Schreibwege des Nutzers sperren (hinzufügen, bearbeiten, löschen, Notiz, Gruppe, Alarme)? */
    fun blocksWrites(keptDatabases: Set<String>): Boolean = isLocked(keptDatabases)

    /**
     * Schreibwege gerade gesperrt (nach [DatabaseEncryptionNotice.storedKept])? Für die Oberfläche, seit Build 104: vor
     * einer Mitteilungs-Erlaubnis prüfen — bei Sperre nur der Hinweis, keine Anfrage (wie Alarme speichern).
     */
    fun blocksWritesNow(): Boolean = blocksWrites(DatabaseEncryptionNotice.storedKept.value)

    /** Alarm speichern bzw. Schnell-Alarm anlegen sperren? */
    fun blocksAlarmSave(keptDatabases: Set<String>): Boolean = isLocked(keptDatabases)

    /** Dauerhinweis der Merkliste, gleich wie iOS (`WatchlistStorageLock.Notice`). */
    enum class Notice {
        /** Gespeicherte Datei da, Schlüssel gerade nicht lesbar: leer, gesperrt ([isLocked]). */
        KEPT,

        /**
         * Kein Schlüssel: nur im Arbeitsspeicher, nie unverschlüsselt geschrieben — nichts wird gespeichert (nicht gesperrt:
         * ohne Datei gibt es keinen gespeicherten Stand zu verlieren, eine alte unverschlüsselte Datei wird seit Build 91 in
         * den Speicher kopiert und bleibt unberührt). Der nächste Start versucht es erneut.
         */
        NOT_SAVED,
    }

    /** Welcher Hinweis gilt nach [DatabaseEncryptionNotice.inMemory] und [DatabaseEncryptionNotice.storedKept]? null = keiner. */
    fun notice(inMemoryDatabases: Set<String>, keptDatabases: Set<String>): Notice? = when {
        isLocked(keptDatabases) -> Notice.KEPT
        AppDatabase.DB_NAME in inMemoryDatabases -> Notice.NOT_SAVED
        else -> null
    }
}
