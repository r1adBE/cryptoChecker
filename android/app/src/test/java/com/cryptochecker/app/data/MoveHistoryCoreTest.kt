package com.cryptochecker.app.data

import com.cryptochecker.app.domain.alarm.MoveWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verlauf des Bewegungs-Alarms mit einem Schlüssel je Paar: append für N Paare bleibt O(N). */
class MoveHistoryCoreTest {

    /** Ablage im Speicher; zählt Schreibvorgänge. */
    private class FakeStorage(initial: Map<String, String> = emptyMap()) : MoveHistoryStorage {
        val map = HashMap(initial)
        var writes = 0
        override fun read(key: String): String? = map[key]
        override fun write(changes: Map<String, String?>) {
            writes++
            changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private val hour = 3_600_000L

    @Test
    fun appendFuerNPaareDekodiertNurDenEigenenVerlauf() {
        val storage = FakeStorage()
        val core = MoveHistoryCore(storage)
        val n = 200
        // Erster Durchlauf (mit dem einmaligen Aufräumen), dann ein zweiter 5 Min. später
        (1..n).forEach { core.append(it.toLong(), MoveWindow.PricePoint(100.0, 1_000L), 1_000L) }
        val afterFirst = core.decodes
        (1..n).forEach { core.append(it.toLong(), MoveWindow.PricePoint(101.0, 301_000L), 301_000L) }
        // Je Paar genau ein Verlauf dekodiert — nicht alle fremden dazu
        assertEquals(n, core.decodes - afterFirst)
        assertEquals(2, core.history(7).size)
    }

    @Test
    fun abgelaufeneFremdeVerlaeufeFallenBeimAufraeumenWeg() {
        val now = 40 * hour
        val storage = FakeStorage(
            mapOf(
                "h_5" to "[[${now - 32 * hour},10.0]]", // älter als die Aufbewahrung (31 h)
                "h_6" to "[[${now - hour},10.0]]",
                "anders" to "bleibt",
            ),
        )
        val core = MoveHistoryCore(storage)
        core.append(1, MoveWindow.PricePoint(100.0, now), now)
        assertFalse(storage.map.containsKey("h_5"))
        assertTrue(storage.map.containsKey("h_6"))
        assertTrue(storage.map.containsKey("anders"))
        assertTrue(storage.map.containsKey("h_1"))
    }

    @Test
    fun ohneNeuenPunktWirdNichtsGeschrieben() {
        val storage = FakeStorage()
        val core = MoveHistoryCore(storage)
        core.append(1, MoveWindow.PricePoint(100.0, 1_000L), 1_000L)
        val writes = storage.writes
        // 10 s später (Live): kein neuer Punkt, Aufräumen nicht fällig
        core.append(1, MoveWindow.PricePoint(100.5, 11_000L), 11_000L)
        assertEquals(writes, storage.writes)
    }
}
