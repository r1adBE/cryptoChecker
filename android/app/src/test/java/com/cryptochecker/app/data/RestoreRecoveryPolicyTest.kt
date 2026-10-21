package com.cryptochecker.app.data

import com.cryptochecker.app.data.RestoreRecoveryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie `RestoreRecoveryPolicyTests.swift`: gleiche Fälle auf beiden Seiten. */
class RestoreRecoveryPolicyTest {
    private val created = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun freshJournalWithoutAttemptIsRolledBack() {
        assertEquals(Decision.ROLL_BACK, RestoreRecoveryPolicy.decide(created, attemptedFor = null, now = created + hour))
        // Ein vermerkter Versuch für ein ANDERES (früheres) Journal zählt nicht
        assertEquals(Decision.ROLL_BACK, RestoreRecoveryPolicy.decide(created, attemptedFor = created - hour, now = created + hour))
        // Genau ein Tag alt: noch zurückrollen
        assertEquals(Decision.ROLL_BACK, RestoreRecoveryPolicy.decide(created, null, now = created + 24 * hour))
    }

    @Test
    fun secondAttemptForSameJournalGivesUp() {
        // Der vorige Start scheiterte mit diesem Journal: kein drittes Zurückrollen über inzwischen geänderte Daten
        assertEquals(Decision.GIVE_UP, RestoreRecoveryPolicy.decide(created, attemptedFor = created, now = created + hour))
    }

    @Test
    fun journalOlderThanOneDayGivesUp() {
        assertEquals(Decision.GIVE_UP, RestoreRecoveryPolicy.decide(created, null, now = created + 24 * hour + 1))
        assertEquals(Decision.GIVE_UP, RestoreRecoveryPolicy.decide(created, null, now = created + 72 * hour))
    }

    @Test
    fun unknownAgeGivesUp() {
        assertEquals(Decision.GIVE_UP, RestoreRecoveryPolicy.decide(0L, null, now = created))
        assertEquals(Decision.GIVE_UP, RestoreRecoveryPolicy.decide(-5L, null, now = created))
    }

    @Test
    fun storedTimeWinsOverFileTime() {
        assertEquals(created, RestoreRecoveryPolicy.createdAt(stored = created, fileModified = created + hour))
        // Journal einer älteren Version ohne «createdAt»: Änderungszeit der Datei
        assertEquals(created + hour, RestoreRecoveryPolicy.createdAt(stored = 0L, fileModified = created + hour))
        assertEquals(0L, RestoreRecoveryPolicy.createdAt(stored = 0L, fileModified = 0L))
    }

    @Test
    fun noticeOnGiveUpOnlyOncePerJournal() {
        // Zu alt, nie versucht: Hinweis jetzt
        assertTrue(RestoreRecoveryPolicy.noticeOnGiveUp(created, attemptedFor = null))
        assertTrue(RestoreRecoveryPolicy.noticeOnGiveUp(created, attemptedFor = created - hour))
        // Der gescheiterte Versuch zeigte ihn schon
        assertFalse(RestoreRecoveryPolicy.noticeOnGiveUp(created, attemptedFor = created))
    }
}
