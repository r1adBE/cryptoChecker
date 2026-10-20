package com.cryptochecker.app.data.local

/**
 * Merkliste in diesem Lauf nur leer im Arbeitsspeicher, obwohl die verschlüsselte Datei mit gespeichertem Stand da ist
 * ([DatabaseEncryptionNotice.storedKept]): Was jetzt angelegt wird, ist nach dem Prozess-Ende weg. Alarme werden darum
 * gar nicht erst angelegt (Hinweis statt stillem Verlust), und der Speicher-Hinweis der Merkliste bleibt stehen (nach
 * jedem anderen Banner kommt er wieder). Paare hinzufügen bleibt erlaubt. Gleich wie iOS (`WatchlistStorageLock`).
 */
object WatchlistStorageLock {

    /** Ist die Merkliste ([AppDatabase.DB_NAME]) unter den nur im Arbeitsspeicher gehaltenen Dateien [keptDatabases]? */
    fun isLocked(keptDatabases: Set<String>): Boolean = AppDatabase.DB_NAME in keptDatabases

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
