package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusszeile am Ende der Merkliste (`testdata/parity/watchlist_footer.json`) — gleiche Fälle wie
 * `WatchlistFooterTests.swift`.
 */
class WatchlistFooterTest {

    private val fixture = ParityJson.load("watchlist_footer.json")

    @Test
    fun grenzeWieInDerApp() {
        assertEquals(WatchlistLimits.MAX_PAIRS, fixture["maxPairs"].num()!!.toInt())
    }

    @Test
    fun fusszeileWieGemeinsameTestfaelle() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 15)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val shown = c["shown"].num()!!.toInt()
            val total = c["total"].num()!!.toInt()
            val selecting = c["selecting"] as Boolean
            val max = c["max"].num()?.toInt()
            val actual = if (max == null) WatchlistFooter.of(shown, total, selecting)
            else WatchlistFooter.of(shown, total, selecting, max)
            val expected = c["expected"]
            if (expected == null) {
                assertNull(name, actual)
                continue
            }
            val e = expected.obj()
            val expectedFooter = WatchlistFooter(
                shown = shown,
                total = total,
                filtered = e["filtered"] as Boolean,
                remaining = e["remaining"].num()?.toInt(),
            )
            assertEquals(name, expectedFooter, actual)
        }
    }

    @Test
    fun naheDerGrenzeWieGemeinsameTestfaelle() {
        val cases = fixture["nearLimit"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val free = c["free"].num()!!.toInt()
            val max = c["max"].num()!!.toInt()
            assertEquals("$free frei von $max", c["expected"] as Boolean, WatchlistFooter.isNearLimit(free, max))
        }
    }

    @Test
    fun zehnPlaetzeBeiDerGrenzeDerApp() {
        assertTrue(WatchlistFooter.isNearLimit(10, WatchlistLimits.MAX_PAIRS))
        assertFalse(WatchlistFooter.isNearLimit(11, WatchlistLimits.MAX_PAIRS))
    }
}
