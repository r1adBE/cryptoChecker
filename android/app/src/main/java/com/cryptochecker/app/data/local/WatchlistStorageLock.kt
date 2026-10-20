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
}
