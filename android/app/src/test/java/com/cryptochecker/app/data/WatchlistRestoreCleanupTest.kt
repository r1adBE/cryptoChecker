package com.cryptochecker.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bereinigung der Merkliste vor dem Wiederherstellen (BackupManager.restore). */
class WatchlistRestoreCleanupTest {

    private data class W(val id: Long, val pair: String, val tag: String = "")
    private data class A(val id: Long, val watchId: Long, val tag: String = "")

    private fun clean(watches: List<W>, alarms: List<A>) = WatchlistRestoreCleanup.clean(
        watches, alarms,
        watchId = { it.id }, pairKey = { it.pair },
        alarmId = { it.id }, alarmWatchId = { it.watchId },
        withWatchId = { a, id -> a.copy(watchId = id) },
    )

    @Test
    fun cleanBackupStaysUnchanged() {
        val watches = listOf(W(1, "BTC"), W(2, "ETH"))
        val alarms = listOf(A(10, 1), A(11, 2))
        val (w, a) = clean(watches, alarms)
        assertEquals(watches, w)
        assertEquals(alarms, a)
    }

    @Test
    fun sameIdTwiceLastWinsAtFirstPosition() {
        val (w, _) = clean(listOf(W(1, "BTC", "alt"), W(2, "ETH"), W(1, "BTC", "neu")), emptyList())
        assertEquals(listOf(W(1, "BTC", "neu"), W(2, "ETH")), w)
    }

    @Test
    fun samePairUnderTwoIdsKeepsFirstAndMovesAlarms() {
        // Vorher: insertWatch (IGNORE) übersprang Id 2, deren Alarm verletzte den Fremdschlüssel
        val (w, a) = clean(listOf(W(1, "BTC"), W(2, "BTC")), listOf(A(10, 1), A(11, 2)))
        assertEquals(listOf(W(1, "BTC")), w)
        assertEquals(listOf(A(10, 1), A(11, 1)), a)
    }

    @Test
    fun orphanAlarmsAreDroppedAndSameAlarmIdLastWins() {
        val (_, a) = clean(listOf(W(1, "BTC")), listOf(A(10, 1, "alt"), A(11, 99), A(10, 1, "neu")))
        assertEquals(listOf(A(10, 1, "neu")), a)
    }

    @Test
    fun entriesWithoutIdAreNeverMerged() {
        val (w, a) = clean(
            listOf(W(0, "BTC"), W(0, "ETH"), W(3, "SOL")),
            listOf(A(0, 3), A(0, 3), A(12, 0)),
        )
        assertEquals(listOf(W(0, "BTC"), W(0, "ETH"), W(3, "SOL")), w)
        // Zwei neue Alarme am festen Paar bleiben; der Alarm am Paar ohne feste Id fällt weg
        assertEquals(listOf(A(0, 3), A(0, 3)), a)
    }
}
