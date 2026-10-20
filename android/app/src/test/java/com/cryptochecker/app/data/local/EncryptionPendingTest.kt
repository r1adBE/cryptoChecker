package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EncryptionPending]: Hinweis «Verschlüsselung noch nicht aktiv» unter Einstellungen › Sicherung. */
class EncryptionPendingTest {

    private val encrypted = Decision.Encrypted(ByteArray(32))

    @Test
    fun plaintextFallbackIsNotedEncryptionClearsIt() {
        val afterFallback = EncryptionPending.updated(emptySet(), "cryptochecker.db", Decision.Plaintext)
        assertEquals(setOf("cryptochecker.db"), afterFallback)
        assertTrue(EncryptionPending.showNotice(afterFallback))
        // Nächster Start verschlüsselt: Hinweis weg
        val afterRetry = EncryptionPending.updated(afterFallback, "cryptochecker.db", encrypted)
        assertFalse(EncryptionPending.showNotice(afterRetry))
    }

    @Test
    fun eachDatabaseCountsOnItsOwn() {
        var pending = EncryptionPending.updated(emptySet(), "cryptochecker.db", Decision.Plaintext)
        pending = EncryptionPending.updated(pending, "portfolio.db", Decision.Plaintext)
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
    fun refusedPortfolioCountsAsNotEncryptedUntilAStartSucceeds() {
        // Kein Schlüssel, Portfolio nur im Arbeitsspeicher: Verschlüsselung noch nicht aktiv
        val refused = EncryptionPending.updated(emptySet(), "portfolio.db", Decision.Refused)
        assertEquals(setOf("portfolio.db"), refused)
        assertTrue(EncryptionPending.showNotice(refused))
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(refused, "portfolio.db", encrypted)))
    }

    @Test
    fun plaintextKeptCountsAsNotEncrypted() {
        // Alte unverschlüsselte Merkliste ohne Schlüssel, nur gelesen (Build 91): Verschlüsselung noch nicht aktiv
        val kept = EncryptionPending.updated(emptySet(), "app.db", Decision.PlaintextKept)
        assertEquals(setOf("app.db"), kept)
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(kept, "app.db", encrypted)))
    }

    @Test
    fun noticeHiddenWhenEverythingIsEncrypted() {
        assertFalse(EncryptionPending.showNotice(emptySet()))
        // Neu angelegt und gleich verschlüsselt (Migration «migrated», beiseitegelegt «setAside»): kein Hinweis
        val migrated = Decision.Encrypted(ByteArray(32), migrated = true)
        val setAside = Decision.Encrypted(ByteArray(32), setAside = true)
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(emptySet(), "a.db", migrated)))
        assertFalse(EncryptionPending.showNotice(EncryptionPending.updated(emptySet(), "b.db", setAside)))
    }
}
