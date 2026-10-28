package com.cryptochecker.app.domain.diagnostics

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.app.util.BackgroundErrorCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Zeile «Background errors» — gleiche Fälle wie `BackgroundErrorsTests.swift`. */
class BackgroundErrorsTest {

    private val data = ParityJson.load("background_errors.json")

    @Test
    fun areasMatchFixture() {
        assertEquals(data["areas"].list().map { it as String }.toSet(), BackgroundErrors.AREAS)
    }

    @Test
    fun sharedLines() {
        data["lines"].list().forEach { raw ->
            val c = raw.obj()
            val counts = c["counts"].obj().mapValues { it.value.num()!!.toInt() }
            val lastAt = (c["lastAt"] as String?)?.let { Instant.parse(it).toEpochMilli() } ?: 0L
            assertEquals(c["name"] as String, c["line"], BackgroundErrors.line(BackgroundErrorSummary(counts, lastAt)))
        }
    }

    @Test
    fun androidLabelsMapToAreas() {
        assertEquals(BackgroundErrors.LOGOS, BackgroundErrors.areaForLabel("CoinLogoSync"))
        assertEquals(BackgroundErrors.REFRESH, BackgroundErrors.areaForLabel("PriceRefresher"))
        assertEquals(BackgroundErrors.LIVE, BackgroundErrors.areaForLabel("LivePriceApplier"))
        assertEquals(BackgroundErrors.WIDGETS, BackgroundErrors.areaForLabel("Listen-Widget neu zeichnen"))
        assertEquals(BackgroundErrors.SCHEDULE, BackgroundErrors.areaForLabel("Neuplanung nach Neustart"))
        assertEquals(BackgroundErrors.ALARMS, BackgroundErrors.areaForLabel("Kursmarke nach «1 h später» scharf stellen"))
        assertEquals(BackgroundErrors.STARTUP, BackgroundErrors.areaForLabel("Hintergrundaufgabe beim Start"))
        assertEquals(BackgroundErrors.OTHER, BackgroundErrors.areaForLabel("Test"))
    }

    @Test
    fun sharedKinds() {
        assertEquals(data["maxKinds"].num()!!.toInt(), BackgroundErrors.MAX_KINDS)
        data["kinds"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(c["raw"] as String, c["kind"], BackgroundErrors.kind(c["raw"] as String))
        }
        data["kindSlots"].list().forEach { raw ->
            val c = raw.obj()
            val known = c["known"].list().map { it as String }.toSet()
            assertEquals(c["name"] as String, c["slot"], BackgroundErrors.kindSlot(known, c["kind"] as String))
        }
        data["kindLines"].list().forEach { raw ->
            val c = raw.obj()
            val kinds = c["kinds"].obj().mapValues { area -> area.value.obj().mapValues { it.value.num()!!.toInt() } }
            assertEquals(c["name"] as String, c["line"], BackgroundErrors.kindsLine(BackgroundErrorSummary(kinds = kinds)))
        }
    }

    @Test
    fun androidKindIsTheSimpleClassNameWithoutMessage() {
        assertEquals("SocketTimeoutException", BackgroundErrors.kindOf(java.net.SocketTimeoutException("geheim 0x12ab")))
        assertEquals("IllegalStateException", BackgroundErrors.kindOf(IllegalStateException("bob")))
        // Anonyme Klasse: Name ohne Paket (gesäubert), nie der Meldungstext
        val anonymous = object : RuntimeException("geheim") {}
        assertTrue(BackgroundErrors.kindOf(anonymous).none { it == '.' || it == '$' })
        assertFalse(BackgroundErrors.kindOf(anonymous).contains("geheim"))
    }

    @Test
    fun counterCountsKindsPerArea() {
        val before = BackgroundErrorCounter.summary().kinds[BackgroundErrors.COINS].orEmpty()
        BackgroundErrorCounter.record(BackgroundErrors.COINS, now = 3_000L, error = java.io.IOException("x"))
        BackgroundErrorCounter.record(BackgroundErrors.COINS, now = 3_000L, kind = BackgroundErrors.ALL_FAILED_KIND)
        BackgroundErrorCounter.record(BackgroundErrors.COINS, now = 3_000L)
        val after = BackgroundErrorCounter.summary().kinds[BackgroundErrors.COINS].orEmpty()
        assertEquals((before["IOException"] ?: 0) + 1, after["IOException"])
        assertEquals((before[BackgroundErrors.ALL_FAILED_KIND] ?: 0) + 1, after[BackgroundErrors.ALL_FAILED_KIND])
        assertTrue(after.size <= BackgroundErrors.MAX_KINDS)
    }

    @Test
    fun counterCountsOnlyKnownAreas() {
        // Ohne install (Unit-Test): nur im Arbeitsspeicher; andere Tests zählen evtl. mit — darum Differenzen
        val before = BackgroundErrorCounter.summary().counts
        BackgroundErrorCounter.record(BackgroundErrors.LOGOS, now = 1_000L)
        BackgroundErrorCounter.record("frei erfundener Text", now = 2_000L)
        val after = BackgroundErrorCounter.summary()
        assertEquals((before[BackgroundErrors.LOGOS] ?: 0) + 1, after.counts[BackgroundErrors.LOGOS])
        assertEquals((before[BackgroundErrors.OTHER] ?: 0) + 1, after.counts[BackgroundErrors.OTHER])
        assertTrue(after.counts.keys.all { it in BackgroundErrors.AREAS })
        assertTrue(after.lastAt >= 2_000L)
    }
}
