package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmLog
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.widget.PortfolioSnapshotSeal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Speicher des Alarm-Protokolls ([AlarmLogStore]): versiegelt, ohne Schlüssel nie Klartext, Live gedrosselt. */
class AlarmLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeKeys(var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) :
        PortfolioSnapshotSeal.Keys {
        override fun lookup(): PortfolioSnapshotSeal.KeyLookup =
            key?.let { PortfolioSnapshotSeal.KeyLookup.Found(it) } ?: PortfolioSnapshotSeal.KeyLookup.Unavailable
        override fun discardIfBroken(error: Throwable) = Unit
    }

    private var clock = 1_760_000_000_000L

    private fun entry(id: Long, outcome: AlarmLogOutcome, notable: Boolean, at: Long = clock) = AlarmLogEntry(
        alarmId = id, at = at, pair = "BTC/CHF", exchange = "Kraken", quote = "CHF", price = 99_800.0,
        condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0, outcome = outcome, notable = notable,
    )

    private fun store(file: File, keys: PortfolioSnapshotSeal.Keys) = AlarmLogStore(file, keys) { clock }

    @Test
    fun writesSealedAndReadsBack() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val keys = FakeKeys()
        store(file, keys).record(listOf(entry(7, AlarmLogOutcome.FIRED, true)), live = false)

        assertTrue(file.exists())
        // Nie Klartext: weder Paar noch Börse lesbar in der Datei
        val raw = file.readText()
        assertFalse(raw.contains("BTC"))
        assertFalse(raw.contains("Kraken"))

        val reloaded = store(file, keys)
        runBlocking { reloaded.load() }
        assertEquals(AlarmLogOutcome.FIRED, reloaded.state.value.lastChecks.getValue(7).outcome)
        assertEquals(1, reloaded.state.value.events.size)
    }

    @Test
    fun withoutKeyNothingIsWrittenAndEntriesWaitInMemory() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val keys = FakeKeys()
        store(file, keys).record(listOf(entry(1, AlarmLogOutcome.FIRED, true)), live = false)
        val before = file.readBytes()

        keys.key?.let { key ->
            keys.key = null
            val locked = store(file, keys)
            locked.record(listOf(entry(2, AlarmLogOutcome.QUIET_HOURS, true)), live = false)
            // Datei unverändert (nicht durch einen Stand ohne die alten Einträge ersetzt)
            assertTrue(before.contentEquals(file.readBytes()))
            assertTrue(2L in locked.state.value.lastChecks)

            // Schlüssel wieder da: alte Einträge gelesen, die wartenden nachgetragen
            keys.key = key
            locked.record(listOf(entry(3, AlarmLogOutcome.NOT_REACHED, false)), live = false)
            assertEquals(setOf(1L, 2L, 3L), locked.state.value.lastChecks.keys)
            assertEquals(2, locked.state.value.events.size)
        }
    }

    @Test
    fun liveChecksAreThrottledButNotableOnesWriteAtOnce() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val keys = FakeKeys()
        val store = store(file, keys)
        store.record(listOf(entry(1, AlarmLogOutcome.NOT_REACHED, false)), live = true)
        val first = file.readBytes()

        clock += 5_000
        store.record(listOf(entry(1, AlarmLogOutcome.NOT_REACHED, false)), live = true)
        assertTrue("gedrosselt", first.contentEquals(file.readBytes()))
        // Im Speicher trotzdem aktuell
        assertEquals(clock, store.state.value.lastChecks.getValue(1).at)

        clock += 1_000
        store.record(listOf(entry(1, AlarmLogOutcome.FIRED, true)), live = true)
        assertFalse("bemerkenswert: sofort", first.contentEquals(file.readBytes()))

        clock += AlarmLog.LIVE_WRITE_INTERVAL_MILLIS
        val beforeInterval = file.readBytes()
        store.record(listOf(entry(1, AlarmLogOutcome.NOT_REACHED, false)), live = true)
        assertFalse("nach dem Intervall wieder geschrieben", beforeInterval.contentEquals(file.readBytes()))
    }

    @Test
    fun liveMergedHoldsAreThrottledButNewEventsWriteAtOnce() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val store = store(file, FakeKeys())
        store.record(listOf(entry(1, AlarmLogOutcome.QUIET_HOURS, true)), live = true)
        val first = file.readBytes()

        // Dieselbe Sperre jede Sekunde: nur «bis» verlängert — gedrosselt
        clock += 1_000
        store.record(listOf(entry(1, AlarmLogOutcome.QUIET_HOURS, true)), live = true)
        assertTrue("verlängert: gedrosselt", first.contentEquals(file.readBytes()))
        assertEquals(clock, store.state.value.events.single().until)

        // Anderes Ergebnis: neuer Eintrag, sofort
        clock += 1_000
        store.record(listOf(entry(1, AlarmLogOutcome.COOLDOWN, true)), live = true)
        val second = file.readBytes()
        assertFalse("neuer Eintrag: sofort", first.contentEquals(second))

        clock += 1_000
        store.record(listOf(entry(1, AlarmLogOutcome.COOLDOWN, true)), live = true)
        assertTrue("verlängert: gedrosselt", second.contentEquals(file.readBytes()))

        // Ausgelöst: immer sofort
        clock += 1_000
        store.record(listOf(entry(1, AlarmLogOutcome.FIRED, true)), live = true)
        assertFalse("ausgelöst: sofort", second.contentEquals(file.readBytes()))
    }

    @Test
    fun readErrorKeepsTheFile() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val keys = FakeKeys()
        store(file, keys).record(listOf(entry(1, AlarmLogOutcome.FIRED, true)), live = false)
        val aside = File(folder.root, "aside")
        assertTrue(file.renameTo(aside))
        // Am Ort der Datei ein Ordner: Lesen scheitert (E/A), nicht «leer»
        assertTrue(file.mkdir())

        val store = store(file, keys)
        store.record(listOf(entry(2, AlarmLogOutcome.FIRED, true)), live = false)
        assertTrue(file.isDirectory)

        assertTrue(file.delete())
        assertTrue(aside.renameTo(file))
        store.record(listOf(entry(3, AlarmLogOutcome.NOT_REACHED, false)), live = false)
        // Alte Einträge nicht verloren, die wartenden nachgetragen
        assertEquals(setOf(1L, 2L, 3L), store.state.value.lastChecks.keys)
        assertEquals(2, store.state.value.events.size)
    }

    @Test
    fun changesWhileLockedAreReplayedAfterLoading() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val keys = FakeKeys()
        store(file, keys).record(
            listOf(entry(7, AlarmLogOutcome.FIRED, true), entry(8, AlarmLogOutcome.FIRED, true)),
            live = false,
        )
        val key = keys.key
        keys.key = null
        val locked = store(file, keys)
        locked.removeAlarm(7)
        locked.clearEvents()
        // Nach dem Löschen notiert: bleibt im Protokoll
        locked.record(listOf(entry(9, AlarmLogOutcome.FIRED, true)), live = false)

        keys.key = key
        runBlocking { locked.load() }
        assertEquals(setOf(8L, 9L), locked.state.value.lastChecks.keys)
        assertEquals(listOf(9L), locked.state.value.events.map { it.alarmId })

        // Auch in der Datei
        val reloaded = store(file, keys)
        runBlocking { reloaded.load() }
        assertEquals(setOf(8L, 9L), reloaded.state.value.lastChecks.keys)
        assertEquals(listOf(9L), reloaded.state.value.events.map { it.alarmId })
    }

    @Test
    fun removeAlarmAndClearArePersisted() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        val keys = FakeKeys()
        val store = store(file, keys)
        store.record(listOf(entry(7, AlarmLogOutcome.FIRED, true), entry(8, AlarmLogOutcome.FIRED, true)), live = false)
        store.removeAlarm(7)
        store.clearEvents()

        val reloaded = store(file, keys)
        runBlocking { reloaded.load() }
        assertEquals(setOf(8L), reloaded.state.value.lastChecks.keys)
        assertTrue(reloaded.state.value.events.isEmpty())
    }

    @Test
    fun damagedFileStartsEmptyAndIsReplaced() {
        val file = File(folder.root, AlarmLogStore.FILE_NAME)
        file.writeText("kein Behälter")
        val store = store(file, FakeKeys())
        store.record(listOf(entry(1, AlarmLogOutcome.FIRED, true)), live = false)
        assertEquals(setOf(1L), store.state.value.lastChecks.keys)
        assertFalse(file.readText() == "kein Behälter")
    }
}
