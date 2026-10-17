package com.cryptochecker.app.data.local

/**
 * Was die Systemsicherung (Auto Backup) je Android-Version mitnimmt — reines Kotlin, getestet zusammen mit den
 * Sicherungsregeln in PortfolioLockPolicyTest.
 *
 * Android 8/8.1 (API 26/27) kennt `requireFlags="clientSideEncryption"` nicht und gäbe jede eingeschlossene Datei auch
 * ohne Ende-zu-Ende-Verschlüsselung an die Cloud. Darum dort keine Kopie der Hauptdatenbank (Build 60): Sie steht
 * nicht in xml/backup_rules.xml, und [SystemBackupAgent] legt sie gar nicht erst an.
 */
object SystemBackupScope {

    /** Ab Android 9 wirkt `requireFlags` in xml-v28/backup_rules.xml (ab Android 12 data_extraction_rules.xml). */
    const val ENCRYPTED_RULES_MIN_SDK = 28

    /** Unverschlüsselte Kopie der Hauptdatenbank (Merkliste, Alarme, Notizen) nur mit verschlüsselter Sicherung. */
    fun writesDatabaseCopy(sdkInt: Int): Boolean = sdkInt >= ENCRYPTED_RULES_MIN_SDK
}
