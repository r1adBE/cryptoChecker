package com.cryptochecker.app.domain.backup

/** Sichern und Wiederherstellen über eine gewählte Datei (Typ F) (Umsetzung: `BackupManager` mit Android-`Uri`). */
interface BackupFiles<F> {
    /** Schreibt die Sicherung; mit [password] verschlüsselt, sonst als lesbares JSON, danach zurückgelesen und verglichen. */
    suspend fun export(uri: F, password: String?)

    suspend fun needsPassword(uri: F): Boolean

    /** Was die Rückfrage vor dem Wiederherstellen nennt. */
    suspend fun preview(uri: F, password: String?): RestorePreview

    suspend fun restore(uri: F, password: String?, acceptInvalid: Int = 0): RestoreResult

    /** Gemerkte gelesene Sicherung verwerfen. */
    fun forgetPickedBackup()

    suspend fun portfolioAlarmCount(): Int
}
