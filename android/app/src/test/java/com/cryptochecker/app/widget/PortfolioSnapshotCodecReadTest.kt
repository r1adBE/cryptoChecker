package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-Teile der Portfolio-Momentaufnahme ([PortfolioSnapshotCodec]): Coins ohne «Heute», ± seit Kauf, Tagesverlauf
 * (samt Hin- und Rückweg über [PortfolioSnapshotCodec.dailyJson]), Veränderung je Zeitraum und Wertverlauf. Fehlende
 * oder unlesbare Teile älterer Aufnahmen laden leer bzw. als null, unlesbare Zeilen entfallen einzeln.
 */
class PortfolioSnapshotCodecReadTest {

    // ---- Coins ----

    @Test
    fun readCoinsSkipsBlankEntries() {
        assertEquals(listOf("BTC", "ETH"), PortfolioSnapshotCodec.readCoins("[\"BTC\", \"\", \"ETH\"]"))
    }

    @Test
    fun readCoinsMissingOrBrokenIsEmpty() {
        assertTrue(PortfolioSnapshotCodec.readCoins(null).isEmpty())
        assertTrue(PortfolioSnapshotCodec.readCoins("kein json").isEmpty())
        assertTrue(PortfolioSnapshotCodec.readCoins("{}").isEmpty())
    }

    // ---- ± seit Kauf ----

    @Test
    fun readChangeWithAndWithoutPercent() {
        assertEquals(PortfolioChange(12.5, 3.25), PortfolioSnapshotCodec.readChange("[12.5, 3.25]"))
        assertEquals(PortfolioChange(-7.0, null), PortfolioSnapshotCodec.readChange("[-7.0]"))
    }

    @Test
    fun readChangeMissingOrUnreadableIsNull() {
        assertNull(PortfolioSnapshotCodec.readChange(null))
        assertNull(PortfolioSnapshotCodec.readChange("[\"abc\"]"))
        assertNull(PortfolioSnapshotCodec.readChange("[]"))
        assertNull(PortfolioSnapshotCodec.readChange("kaputt"))
    }

    // ---- Tagesverlauf ----

    @Test
    fun readDailySortsByDayAndReadsOptionalCandle() {
        val json = "[[20000, 110.0, 100.0, 120.0, 90.0], [19999, 95.0]]"
        val points = PortfolioSnapshotCodec.readDaily(json)
        assertEquals(
            listOf(
                PortfolioHistoryPoint(19_999L, 95.0),
                PortfolioHistoryPoint(20_000L, 110.0, 100.0, 120.0, 90.0),
            ),
            points,
        )
    }

    @Test
    fun readDailySkipsUnreadableRows() {
        // Zahl statt Zeile, Zeile ohne Wert und Wert als Text entfallen
        val json = "[5, [20001], [20002, \"x\"], [20003, 42.0]]"
        assertEquals(listOf(PortfolioHistoryPoint(20_003L, 42.0)), PortfolioSnapshotCodec.readDaily(json))
    }

    @Test
    fun readDailyMissingOrBrokenIsEmpty() {
        assertTrue(PortfolioSnapshotCodec.readDaily(null).isEmpty())
        assertTrue(PortfolioSnapshotCodec.readDaily("{").isEmpty())
    }

    @Test
    fun dailyJsonRoundTripsThroughReadDaily() {
        val points = listOf(
            PortfolioHistoryPoint(20_000L, 110.0, 100.0, 120.0, 90.0),
            PortfolioHistoryPoint(20_001L, 111.5),
        )
        val json = PortfolioSnapshotCodec.dailyJson(points).toString()
        assertEquals(points, PortfolioSnapshotCodec.readDaily(json))
    }

    @Test
    fun dailyJsonDropsNonFiniteValuesAndIncompleteCandles() {
        val points = listOf(
            PortfolioHistoryPoint(20_000L, Double.NaN),
            // Kerze unvollständig (Tief fehlt): nur Tag und Wert
            PortfolioHistoryPoint(20_001L, 50.0, 49.0, 51.0, null),
            // Kerze mit unendlichem Hoch: nur Tag und Wert
            PortfolioHistoryPoint(20_002L, 60.0, 59.0, Double.POSITIVE_INFINITY, 58.0),
        )
        val array = PortfolioSnapshotCodec.dailyJson(points)
        assertEquals(2, array.length())
        assertEquals(2, array.getJSONArray(0).length())
        assertEquals(2, array.getJSONArray(1).length())
        assertEquals(
            listOf(PortfolioHistoryPoint(20_001L, 50.0), PortfolioHistoryPoint(20_002L, 60.0)),
            PortfolioSnapshotCodec.readDaily(array.toString()),
        )
    }

    @Test
    fun dailyJsonOfEmptyListIsEmptyArray() {
        assertEquals(0, PortfolioSnapshotCodec.dailyJson(emptyList()).length())
    }

    // ---- Veränderung je Zeitraum ----

    @Test
    fun readPeriodChangesReadsDaysPercentAndSkippedCoins() {
        val json = "{\"7\": [10.0, 1.5, [\"SOL\", \"\"]], \"30\": [-5.0], \"x\": [1.0], \"365\": [\"a\"], \"90\": 3}"
        val changes = PortfolioSnapshotCodec.readPeriodChanges(json)
        assertEquals(
            mapOf(
                7 to PortfolioChange(10.0, 1.5, listOf("SOL")),
                30 to PortfolioChange(-5.0, null, emptyList()),
            ),
            changes,
        )
    }

    @Test
    fun readPeriodChangesMissingOrBrokenIsEmpty() {
        assertTrue(PortfolioSnapshotCodec.readPeriodChanges(null).isEmpty())
        assertTrue(PortfolioSnapshotCodec.readPeriodChanges("[1, 2]").isEmpty())
    }

    // ---- Wertverlauf ----

    @Test
    fun readValueHistorySortsByTimeAndSkipsInvalidPoints() {
        val json = "[{\"t\": 2000, \"v\": 5.0}, {\"t\": 1000, \"v\": 4.0}, {\"t\": 0, \"v\": 1.0}, {\"t\": 3000}, 7]"
        assertEquals(
            listOf(PortfolioValuePoint(1000L, 4.0), PortfolioValuePoint(2000L, 5.0)),
            PortfolioSnapshotCodec.readValueHistory(json),
        )
    }

    @Test
    fun readValueHistoryMissingOrBrokenIsEmpty() {
        assertTrue(PortfolioSnapshotCodec.readValueHistory(null).isEmpty())
        assertTrue(PortfolioSnapshotCodec.readValueHistory("nicht lesbar").isEmpty())
    }
}
