package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie LiveStatusTests.swift (gleiche Fälle). */
class LiveStatusTest {

    private val min = 60_000L

    @Test
    fun pollingThresholdIsThreeBackgroundIntervalsWithFloor() {
        // Beide Modi: kein Vordergrund-Takt, also die Hintergrund-Regel
        assertEquals(45 * min, LiveStatus.pollingStaleAfterMillis(15))
        assertEquals(90 * min, LiveStatus.pollingStaleAfterMillis(30))
        assertEquals(180 * min, LiveStatus.pollingStaleAfterMillis(60))
        // Untergrenze 15 Min.
        assertEquals(15 * min, LiveStatus.pollingStaleAfterMillis(1))
    }

    @Test
    fun livePairStaleAfterTwoMinutes() {
        assertEquals(2 * min, LiveStatus.staleAfterMillis(onLiveSocket = true, pollingAfter = 45 * min))
        assertEquals(45 * min, LiveStatus.staleAfterMillis(onLiveSocket = false, pollingAfter = 45 * min))
        // 0 = nie veraltet (Vorschau im Aktionsblatt) bleibt 0
        assertEquals(0L, LiveStatus.staleAfterMillis(onLiveSocket = true, pollingAfter = 0L))
    }

    @Test
    fun liveOnlyWhenAllPairsOnSocketAndFresh() {
        assertTrue(LiveStatus.allLive(listOf(1L, 2L), setOf(1L, 2L, 3L), stale = 0))
        // Ein Paar abgefragt (Börse ohne WebSocket): kein «Live»
        assertFalse(LiveStatus.allLive(listOf(1L, 2L), setOf(1L), stale = 0))
        // Ein Live-Paar ohne Kurs seit über 2 Min.: kein «Live»
        assertFalse(LiveStatus.allLive(listOf(1L, 2L), setOf(1L, 2L), stale = 1))
        // Keine Paare / kein Strom (Modus «Sparsam»)
        assertFalse(LiveStatus.allLive(emptyList(), setOf(1L), stale = 0))
        assertFalse(LiveStatus.allLive(listOf(1L), emptySet(), stale = 0))
    }

    @Test
    fun shownTimeUsesPolledPairsWhenMixed() {
        val updates = listOf(1L to 10_000L, 2L to 4_000L, 3L to 6_000L)
        // Ohne Strom: die jüngste
        assertEquals(10_000L, LiveStatus.shownTime(updates, emptySet()))
        // Paar 1 live: die jüngste der abgefragten (3)
        assertEquals(6_000L, LiveStatus.shownTime(updates, setOf(1L)))
        // Alle live: die jüngste
        assertEquals(10_000L, LiveStatus.shownTime(updates, setOf(1L, 2L, 3L)))
        // Abgefragte nie aktualisiert: die jüngste
        assertEquals(10_000L, LiveStatus.shownTime(listOf(1L to 10_000L, 2L to 0L), setOf(1L)))
        assertEquals(0L, LiveStatus.shownTime(emptyList(), setOf(1L)))
    }
}
