package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseKeyRetry.Failure
import com.cryptochecker.app.data.local.DatabaseKeyRetry.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Seit Build 102: vorübergehende Keystore-Fehler ([DatabaseKeyRetry]) — «verdächtige» Fehler werden wiederholt und erst in
 * einem späteren Lauf als Verlust gewertet; nie im selben Lauf beiseitelegen oder den Keystore-Eintrag ersetzen.
 */
class DatabaseKeyRetryTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Steht für `UnrecoverableKeyException` (Android-Klasse, im JVM-Test nicht nötig). */
    private class Suspicious : Exception("unrecoverable")

    /** Steht für `KeyPermanentlyInvalidatedException` bzw. `AEADBadTagException`. */
    private class Definite : Exception("invalidated")

    private val sleeps = mutableListOf<Long>()

    private fun classify(e: Throwable) = when (e) {
        is Definite -> Failure.PERMANENT
        is Suspicious -> Failure.SUSPECT
        else -> Failure.TRANSIENT
    }

    private fun <T : Any> attempt(block: () -> T?) = DatabaseKeyRetry.run(3, 500L, { sleeps += it }, { classify(it) }, block)

    @Test
    fun verdaechtigerFehlerBeimErstenVersuchDannGefunden() {
        var calls = 0
        val outcome = attempt {
            calls++
            if (calls == 1) throw Suspicious() else "schlüssel"
        }
        assertEquals("schlüssel", (outcome as Outcome.Value).value)
        assertEquals(2, calls)
        assertEquals(listOf(500L), sleeps)
    }

    @Test
    fun keinSchluesselWirdWiederholt() {
        var calls = 0
        val outcome = attempt<String> {
            calls++
            if (calls < 3) null else "spät"
        }
        assertEquals("spät", (outcome as Outcome.Value).value)
        assertEquals(listOf(500L, 500L), sleeps)
    }

    @Test
    fun dreimalVerdaechtigBleibtVerdaechtig() {
        var calls = 0
        val outcome = attempt<String> {
            calls++
            throw Suspicious()
        }
        assertTrue(outcome is Outcome.Suspect)
        assertTrue((outcome as Outcome.Suspect).error is Suspicious)
        assertEquals(3, calls)
        // Nach dem letzten Versuch nicht mehr warten
        assertEquals(2, sleeps.size)
    }

    @Test
    fun dreimalKeinSchluesselIstVerdaechtigOhneFehler() {
        val outcome = attempt<String> { null }
        assertNull((outcome as Outcome.Suspect).error)
    }

    @Test
    fun eindeutigerFehlerBrichtSofortAb() {
        var calls = 0
        val outcome = attempt<String> {
            calls++
            throw Definite()
        }
        assertTrue(outcome is Outcome.Permanent)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun voruebergehenderFehlerBleibtVoruebergehend() {
        val outcome = attempt<String> { throw IllegalStateException("beschäftigt") }
        assertTrue(outcome is Outcome.Transient)
    }

    @Test
    fun verlustGiltErstImNaechstenLauf() {
        val dir = folder.newFolder("db_keys")
        val firstRun = DatabaseKeyRetry.SuspectMarker(dir, mutableSetOf())
        // Erster Verdacht: nicht verloren, Merker angelegt — auch ein zweiter Aufruf im selben Lauf nicht
        assertFalse(firstRun.escalate("cryptochecker.db"))
        assertFalse(firstRun.escalate("cryptochecker.db"))
        assertTrue(dir.resolve("cryptochecker.db" + DatabaseKeyRetry.SuspectMarker.SUFFIX).exists())
        // Nächster Lauf (neuer Prozess): jetzt als verloren werten, auch beim Ersetzen gleich danach
        val secondRun = DatabaseKeyRetry.SuspectMarker(dir, mutableSetOf())
        assertTrue(secondRun.escalate("cryptochecker.db"))
        assertTrue(secondRun.escalate("cryptochecker.db"))
        // Die andere Datenbank ist unberührt
        assertFalse(secondRun.escalate("portfolio.db"))
    }

    @Test
    fun wiederLesbarerSchluesselLoeschtDenVerdacht() {
        val dir = folder.newFolder("db_keys")
        DatabaseKeyRetry.SuspectMarker(dir, mutableSetOf()).escalate("cryptochecker.db")
        val secondRun = DatabaseKeyRetry.SuspectMarker(dir, mutableSetOf())
        secondRun.clear("cryptochecker.db")
        assertFalse(dir.resolve("cryptochecker.db" + DatabaseKeyRetry.SuspectMarker.SUFFIX).exists())
        // Ein neuer Verdacht beginnt wieder von vorn
        assertFalse(DatabaseKeyRetry.SuspectMarker(dir, mutableSetOf()).escalate("cryptochecker.db"))
    }

    @Test
    fun merkerPasstNichtZuFrueherenSchluesseln() {
        val base = "cryptochecker.db.key" + DatabaseKeyStore.PREVIOUS_SUFFIX
        assertNull(DatabaseKeyStore.slotNumber("cryptochecker.db" + DatabaseKeyRetry.SuspectMarker.SUFFIX, base))
    }
}
