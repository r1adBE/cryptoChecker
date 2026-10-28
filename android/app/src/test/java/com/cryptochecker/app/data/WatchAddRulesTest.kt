package com.cryptochecker.app.data

import com.cryptochecker.app.data.FakeWatchStore.Companion.BINANCE
import com.cryptochecker.app.data.FakeWatchStore.Companion.key
import com.cryptochecker.app.data.FakeWatchStore.Companion.pair
import com.cryptochecker.app.domain.watch.WatchlistLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Als Verhalten: «Rückgängig» nach dem Löschen umgeht die Obergrenze der Merkliste nicht, und ein noch
 * bzw. wieder vorhandenes Paar geht vor «voll» — wie beim Anlegen. Geprüft an den Regeln, die [tryAddWatch] und
 * [restore] in ihrer Transaktion benutzen ([addBlocker], [restoreBlocker]), und am Ablauf Löschen → Zurückholen auf
 * einer Merkliste im Speicher ([FakeWatchStore]). iOS: `WatchlistLimitsTests` (`AppData.restoreInto`).
 */
class WatchAddRulesTest {

    @Test
    fun duplicateGoesBeforeFullAndCountIsOnlyReadWithoutDuplicate() {
        var reads = 0
        val full = { reads++; WatchlistLimits.MAX_PAIRS }
        assertEquals(AddWatchOutcome.Duplicate, addBlocker(exists = true, count = full))
        assertEquals("Zählen nur ohne Dublette", 0, reads)
        assertEquals(AddWatchOutcome.Full, addBlocker(exists = false, count = full))
        assertNull(addBlocker(exists = false) { WatchlistLimits.MAX_PAIRS - 1 })
        assertEquals(RestoreOutcome.BLOCKED, restoreBlocker(present = true, count = full))
        assertEquals(1, reads)
        assertEquals(RestoreOutcome.FULL, restoreBlocker(present = false, count = full))
        assertNull(restoreBlocker(present = false) { 0 })
    }

    @Test
    fun undoRestoresSameIdWhileThereIsRoom() {
        val store = FakeWatchStore()
        val id = (store.add(BINANCE, pair("BTC")) as AddWatchOutcome.Added).id
        store.delete(listOf(id))
        assertEquals(RestoreOutcome.RESTORED, store.restore(id, key(BINANCE, pair("BTC"))))
        assertEquals(listOf(id), store.entries.keys.toList())
        // Zweites Zurückholen: Paar ist schon da
        assertEquals(RestoreOutcome.BLOCKED, store.restore(id, key(BINANCE, pair("BTC"))))
    }

    @Test
    fun undoOnFullWatchlistIsRefusedAndWritesNothing() {
        val store = FakeWatchStore(filler = WatchlistLimits.MAX_PAIRS - 1)
        val id = (store.add(BINANCE, pair("BTC")) as AddWatchOutcome.Added).id
        store.delete(listOf(id))
        // Inzwischen wieder voll (ein anderes Paar angelegt)
        assertEquals(AddWatchOutcome.Added(id + 1), store.add(BINANCE, pair("ETH")))
        store.writes.clear()
        assertEquals(RestoreOutcome.FULL, store.restore(id, key(BINANCE, pair("BTC"))))
        assertEquals(emptyList<String>(), store.writes)
        assertEquals(WatchlistLimits.MAX_PAIRS, store.count)
        // Neu anlegen ist ebenso voll
        assertEquals(AddWatchOutcome.Full, store.add(BINANCE, pair("BTC")))
    }

    @Test
    fun pairAddedAgainBlocksUndoEvenWhenFull() {
        val store = FakeWatchStore(filler = WatchlistLimits.MAX_PAIRS - 1)
        val id = (store.add(BINANCE, pair("BTC")) as AddWatchOutcome.Added).id
        store.delete(listOf(id))
        // Dasselbe Paar unter neuer Id angelegt: die Merkliste ist voll, «schon vorhanden» geht aber vor
        store.add(BINANCE, pair("BTC"))
        assertEquals(WatchlistLimits.MAX_PAIRS, store.count)
        assertEquals(RestoreOutcome.BLOCKED, store.restore(id, key(BINANCE, pair("BTC"))))
        assertEquals(AddWatchOutcome.Duplicate, store.add(BINANCE, pair("BTC")))
    }
}
