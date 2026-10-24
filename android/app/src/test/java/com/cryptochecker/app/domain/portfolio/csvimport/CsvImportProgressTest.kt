package com.cryptochecker.app.domain.portfolio.csvimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Fortschritt und Abbruch des CSV-Imports ([CsvProgressThrottle], [CsvImportInsert], [CsvImportMapping.preview] mit
 * `onRow`) — wie `CsvImportProgressTests.swift`.
 */
class CsvImportProgressTest {

    /** Relai-Export mit [rows] Käufen (je eigene Kennung, CHF-Preis). */
    private fun relai(rows: Int): String = buildString {
        append("Date,Transaction Type,BTC Amount,BTC Price,Currency Pair,Fiat Amount (excl. fees),Fiat Currency,")
        append("Fee,Fee Currency,Destination,Operation ID,Counterparty\r\n")
        for (i in 0 until rows) {
            val minute = i % 60
            val hour = (i / 60) % 24
            val day = 1 + i / 1440
            append("2024-03-%02dT%02d:%02d:00Z,".format(day, hour, minute))
            append("Buy,0.001,60000.00,BTC/CHF,60.00,CHF,0.50,CHF,bc1q,op-%05d,Relai\r\n".format(i))
        }
    }

    private val fx: (String, Long) -> BigDecimal? = { c, _ -> if (c == "CHF") BigDecimal("0.8") else null }

    @Test
    fun throttleReportsAtMostEveryHundredRowsAndFiftyMillisAndEndsAtTotal() {
        var now = 0L
        val seen = ArrayList<CsvImportProgress>()
        val throttle = CsvProgressThrottle(CsvImportStep.SAVE, total = 1_000, clock = { now }) { seen += it }
        throttle.start()
        for (row in 1..1_000) {
            now += 1 // 1 ms je Zeile
            throttle.row(row)
        }
        assertEquals(CsvImportProgress(CsvImportStep.SAVE, 0, 1_000), seen.first())
        assertEquals(CsvImportProgress(CsvImportStep.SAVE, 1_000, 1_000), seen.last())
        // 1 ms je Zeile: 100 Zeilen = 100 ms, also greift die Grenze von 100 Zeilen
        val steps = seen.zipWithNext { a, b -> b.done - a.done }
        assertTrue("höchstens alle 100 Zeilen: $steps", steps.dropLast(1).all { it >= CsvProgressThrottle.MIN_ROWS })
        assertEquals("Ende genau einmal", 1, seen.count { it.done == 1_000 })
        assertTrue(seen.size <= 1 + 1_000 / CsvProgressThrottle.MIN_ROWS + 1)
    }

    @Test
    fun throttleWaitsForFiftyMillisWhenRowsAreFast() {
        var now = 0L
        val seen = ArrayList<Int>()
        val throttle = CsvProgressThrottle(CsvImportStep.PREVIEW, total = 10_000, clock = { now }) { seen += it.done }
        for (row in 1..10_000) {
            if (row % 1_000 == 0) now += 50 // 1000 Zeilen je 50 ms
            throttle.row(row)
        }
        // Alle 1000 Zeilen einmal (Zeit), nicht alle 100; letzte Zeile genau einmal
        assertEquals((1..10).map { it * 1_000 }, seen)
    }

    @Test
    fun throttleIgnoresRepeatsAndReportsLastRowEvenWhenQuick() {
        val seen = ArrayList<Int>()
        val throttle = CsvProgressThrottle(CsvImportStep.SAVE, total = 3, clock = { 0L }) { seen += it.done }
        throttle.row(1)
        throttle.row(3)
        throttle.row(3)
        assertEquals(listOf(3), seen)
        assertEquals(0.5f, CsvImportProgress(CsvImportStep.SAVE, 1, 2).fraction, 0f)
        assertEquals(0f, CsvImportProgress(CsvImportStep.SAVE, 0, 0).fraction, 0f)
    }

    /** Wie eine Datenbank-Transaktion: Zeilen erst vorgemerkt, gespeichert nur ohne Ausnahme. */
    private class FakeStore {
        val saved = ArrayList<String>()
        fun <R> transaction(block: (MutableList<String>) -> R): R {
            val staged = ArrayList<String>()
            val result = block(staged)
            saved += staged
            return result
        }
    }

    @Test
    fun insertSkipsKnownKeysReportsProgressToTotalAndCountsLikeBefore() {
        val store = FakeStore()
        val items = listOf("a", "b", "a", "c", "old")
        val rows = ArrayList<Int>()
        val count = store.transaction { staged ->
            CsvImportInsert.run(items, hashSetOf("old"), { listOf(it) }, { false }, { rows += it }) {
                staged += it
                true
            }
        }
        assertEquals(3, count)
        assertEquals(listOf("a", "b", "c"), store.saved)
        assertEquals(listOf(1, 2, 3, 4, 5), rows)
    }

    @Test
    fun cancelledInsertThrowsAndSavesNothing() {
        val store = FakeStore()
        val items = (1..10_000).map { "k$it" }
        var cancel = false
        val rows = ArrayList<Int>()
        try {
            store.transaction { staged ->
                val onRow = { done: Int ->
                    rows += done
                    if (done == 4_321) cancel = true
                }
                CsvImportInsert.run(items, HashSet(), { listOf(it) }, { cancel }, onRow) { staged += it; true }
            }
            fail("Abbruch erwartet")
        } catch (_: CsvImportCancelledException) {
            // erwartet
        }
        assertTrue("nichts gespeichert (kein Teil-Import)", store.saved.isEmpty())
        assertEquals(4_321, rows.last())
    }

    @Test
    fun previewWithProgressEqualsPreviewWithoutAndEndsAtTotal() {
        val result = CsvImportParser.parse(relai(1_050))
        val plain = CsvImportMapping.preview(result, emptySet(), fx = fx)
        val reported = ArrayList<Pair<Int, Int>>()
        val withProgress = CsvImportMapping.preview(result, emptySet(), onRow = { d, t -> reported += d to t }, fx = fx)
        assertEquals(plain, withProgress)
        assertEquals(1_050, plain.transactions.size)
        assertEquals(1_050 to 1_050, reported.last())
        assertEquals(listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1_000, 1_050), reported.map { it.first })
        assertTrue(plain.missingFx.isEmpty())
    }

    @Test
    fun previewStopsWhenOnRowThrows() {
        val result = CsvImportParser.parse(relai(500))
        var calls = 0
        try {
            val stop = { _: Int, _: Int -> calls++; throw IllegalStateException("abgebrochen") }
            CsvImportMapping.preview(result, emptySet(), onRow = stop, fx = fx)
            fail("Abbruch erwartet")
        } catch (_: IllegalStateException) {
            // erwartet
        }
        assertEquals("nach dem ersten Block abgebrochen", 1, calls)
    }
}
