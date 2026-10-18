package com.cryptochecker.app.data

import java.security.MessageDigest

/**
 * Prüfung einer eben geschriebenen Sicherungsdatei (reines Kotlin, gespiegelt in `BackupManager.verifyWritten` auf iOS):
 * zurückgelesen muss sie genau die geschriebenen Bytes enthalten — gleiche Grösse und gleicher SHA-256. Manche
 * Speicher-Anbieter bestätigen das Schreiben, legen aber eine gekürzte, leere oder umkodierte Datei ab; das zeigte sich
 * sonst erst beim Wiederherstellen, wenn das Gerät vielleicht schon weg ist.
 */
object BackupVerification {

    /** null = Datei stimmt; sonst eine kurze Beschreibung der Abweichung (fürs Protokoll, keine Inhalte). */
    fun check(written: ByteArray, readBack: ByteArray): String? = when {
        readBack.size != written.size -> "Grösse ${readBack.size} statt ${written.size} Bytes"
        !sha256(readBack).contentEquals(sha256(written)) -> "Inhalt weicht ab (SHA-256)"
        else -> null
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
