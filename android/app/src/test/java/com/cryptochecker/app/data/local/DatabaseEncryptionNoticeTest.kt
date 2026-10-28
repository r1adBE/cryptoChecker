package com.cryptochecker.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DatabaseEncryptionNotice.isStoredKept]: Nur eine gesperrt behaltene Datei bringt mit dem nächsten Prozess den
 * gespeicherten Stand zurück — ohne Datei ([DatabaseOpenPlanner.Decision.Refused]) ist mit dem Lauf auch der Alarm weg.
 * Eigene Namen je Fall: Der Zustand gilt für den ganzen Prozess.
 */
class DatabaseEncryptionNoticeTest {

    @Test
    fun keptFileIsStoredKept() {
        val name = "test_stored_kept.db"
        DatabaseEncryptionNotice.markInMemory(name, storedKept = true)
        assertTrue(DatabaseEncryptionNotice.isInMemory(name))
        assertTrue(DatabaseEncryptionNotice.isStoredKept(name))
    }

    @Test
    fun refusedWithoutFileIsNotStoredKept() {
        val name = "test_refused.db"
        DatabaseEncryptionNotice.markInMemory(name)
        assertTrue(DatabaseEncryptionNotice.isInMemory(name))
        assertFalse(DatabaseEncryptionNotice.isStoredKept(name))
        // Nie geöffnet: normal
        assertFalse(DatabaseEncryptionNotice.isStoredKept("test_never_opened.db"))
    }
}
