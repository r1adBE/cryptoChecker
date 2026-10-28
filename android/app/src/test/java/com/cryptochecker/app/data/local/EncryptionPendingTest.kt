package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EncryptionPending]: Hinweis «Verschlüsselung noch nicht aktiv» unter Einstellungen › Sicherung. */
class EncryptionPendingTest {

    private val encrypted = Decision.Encrypted(ByteArray(32))

    @Test
    fun refusedIsNotedEncryptionClearsIt() {
        // Kein Schlüssel, Datenbank nur im Arbeitsspeicher: Verschlüsselung noch nicht aktiv
        val refused = EncryptionPending.updated(emptySet(), "portfolio.db", Decision.Refused)
        assertEquals(setOf("portfolio.db"), refused)
        assertTrue(EncryptionPending.showNotice(refused))
        // Nächster Start mit Schlüssel: Hinweis weg
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(refused, "portfolio.db", encrypted)))
    }

    @Test
    fun eachDatabaseCountsOnItsOwn() {
        var pending = EncryptionPending.updated(emptySet(), "cryptochecker.db", Decision.Refused)
        pending = EncryptionPending.updated(pending, "portfolio.db", Decision.Refused)
        pending = EncryptionPending.updated(pending, "cryptochecker.db", encrypted)
        assertEquals(setOf("portfolio.db"), pending)
        assertTrue(EncryptionPending.showNotice(pending))
    }

    @Test
    fun unavailableKeyLeavesTheStateAsItWas() {
        val unavailable = Decision.KeyUnavailable(IllegalStateException("locked"))
        assertEquals(emptySet<String>(), EncryptionPending.updated(emptySet(), "portfolio.db", unavailable))
        assertEquals(setOf("portfolio.db"), EncryptionPending.updated(setOf("portfolio.db"), "portfolio.db", unavailable))
    }

    @Test
    fun noticeHiddenWhenEverythingIsEncrypted() {
        assertFalse(EncryptionPending.showNotice(emptySet()))
        // Neu angelegt bzw. beiseitegelegt und neu begonnen: kein Hinweis
        val setAside = Decision.Encrypted(ByteArray(32), setAside = true)
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(emptySet(), "a.db", encrypted)))
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(emptySet(), "b.db", setAside)))
    }
}
