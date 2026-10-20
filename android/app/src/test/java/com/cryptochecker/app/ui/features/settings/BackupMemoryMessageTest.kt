package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.data.BackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Zu wenig Arbeitsspeicher für Argon2id (64 MiB am Stück): verständliche Meldung statt Absturz oder «fehlgeschlagen»,
 * und die App fordert den grossen Heap an, damit die Sicherung auch auf Geräten mit 2 GB RAM einspielbar ist.
 */
class BackupMemoryMessageTest {

    @Test
    fun notEnoughMemoryHasOwnMessage() {
        assertEquals(BackupMessage.NotEnoughMemory, BackupMessage.failure(BackupCrypto.NotEnoughMemoryException()))
        // Andere Fehler bleiben «fehlgeschlagen»
        assertEquals(BackupMessage.Failed, BackupMessage.failure(IllegalStateException("x")))
    }

    @Test
    fun storageAndSizeRefusalsHaveOwnMessages() {
        assertEquals(BackupMessage.StorageKept, BackupMessage.failure(com.cryptochecker.app.data.BackupStorageKeptException()))
        assertEquals(BackupMessage.StorageInMemory, BackupMessage.failure(com.cryptochecker.app.data.BackupStorageInMemoryException()))
        // Zu gross zum Schreiben ist nicht «Datei zu gross oder keine Sicherung» (das gilt beim Einlesen)
        assertEquals(BackupMessage.ExportTooLarge, BackupMessage.failure(com.cryptochecker.app.data.BackupLimits.ExportTooLargeException(1)))
        assertEquals(BackupMessage.TooLarge, BackupMessage.failure(com.cryptochecker.app.data.BackupLimits.TooLargeException()))
    }

    @Test
    fun manifestRequestsLargeHeap() {
        // Unit-Tests laufen im Modul-Ordner (android/app)
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        val application = manifest.readText().substringAfter("<application").substringBefore(">")
        assertTrue("android:largeHeap=\"true\" fehlt im <application>", application.contains("android:largeHeap=\"true\""))
    }
}
