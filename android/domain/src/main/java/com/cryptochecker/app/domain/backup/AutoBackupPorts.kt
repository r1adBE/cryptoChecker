package com.cryptochecker.app.domain.backup

import kotlinx.coroutines.flow.StateFlow

/** Einstellungen der automatischen Sicherung: Schalter, Ordner, Passwort (Umsetzung: `AutoBackupStore`). */
interface AutoBackupSettings {
    val state: StateFlow<AutoBackupState>

    /** Einschalten mit Ordner und Passwort; false = Passwort nicht versiegelt. */
    fun enable(folderUri: String, folderName: String?, password: String): Boolean

    fun changeFolder(folderUri: String, folderName: String?)

    fun disable()
}

/** Läufe der automatischen Sicherung (Umsetzung: `AutoBackupRunner`). */
interface AutoBackupJobs {
    /** Beim Öffnen der App, falls fällig — kehrt sofort zurück. */
    fun startIfDue()

    /** «Jetzt sichern»; false = gescheitert oder nicht eingerichtet. */
    suspend fun run(now: Long = System.currentTimeMillis()): Boolean
}
