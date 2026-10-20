package com.cryptochecker.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DatabaseEncryptionNotice.revertsOnRestart]: Kopie einer alten unverschlüsselten Datei
 * ([DatabaseEncryptionMigration.Decision.PlaintextKept]) wie «gespeicherter Stand gerade nicht lesbar» behandeln — «ausgelöst» eines Alarms ginge mit dem Prozess verloren, und
 * jede Prüfung im Hintergrund meldete dieselben Alarme erneut. Eigene Namen je Fall: Der Zustand gilt für den ganzen Prozess.
 */
class DatabaseEncryptionNoticeTest {

    @Test
    fun plaintextCopyRevertsOnRestartButIsNotLocked() {
        val name = "test_plaintext_copy.db"
        DatabaseEncryptionNotice.markInMemory(name, copied = true)
        assertTrue(DatabaseEncryptionNotice.isInMemory(name))
        assertTrue(DatabaseEncryptionNotice.isVolatileCopy(name))
        assertTrue(DatabaseEncryptionNotice.revertsOnRestart(name))
        // Nicht gesperrt: Paare und Alarme sind die gespeicherten (Hinweis bleibt «wird nicht gespeichert»)
        assertFalse(DatabaseEncryptionNotice.isStoredKept(name))
    }

    @Test
    fun keptFileRevertsOnRestart() {
        val name = "test_stored_kept.db"
        DatabaseEncryptionNotice.markInMemory(name, storedKept = true)
        assertTrue(DatabaseEncryptionNotice.revertsOnRestart(name))
        assertFalse(DatabaseEncryptionNotice.isVolatileCopy(name))
    }

    @Test
    fun refusedWithoutFileDoesNotRevert() {
        // Ohne Datei ist mit dem Lauf auch der Alarm weg: nichts meldet nach dem Neustart erneut
        val name = "test_refused.db"
        DatabaseEncryptionNotice.markInMemory(name)
        assertTrue(DatabaseEncryptionNotice.isInMemory(name))
        assertFalse(DatabaseEncryptionNotice.revertsOnRestart(name))
        // Nie geöffnet: normal
        assertFalse(DatabaseEncryptionNotice.revertsOnRestart("test_never_opened.db"))
    }
}
