package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSectionsTest {

    @Test
    fun startsWithNow() {
        assertEquals(MarketSection.NOW, MarketSections.DEFAULT)
    }

    @Test
    fun slotsBelongToTheirRegister() {
        assertEquals(MarketSection.NOW, MarketSections.of(MarketRevealSlot.PULSE))
        assertEquals(MarketSection.NOW, MarketSections.of(MarketRevealSlot.UNUSUAL))
        assertEquals(MarketSection.CONTEXT, MarketSections.of(MarketRevealSlot.FEAR_GREED))
        assertEquals(MarketSection.CONTEXT, MarketSections.of(MarketRevealSlot.HALVING))
        assertEquals(MarketSection.DATA, MarketSections.of(MarketRevealSlot.MARKET_TOTALS))
        assertEquals(MarketSection.DATA, MarketSections.of(MarketRevealSlot.COIN))
        // Jedes Register hat zusammenhängende Teile in der Reihenfolge der Register
        val order = MarketRevealSlot.entries.map { MarketSections.of(it).ordinal }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun loadingRowUntilLastSlotOfRegister() {
        assertEquals(MarketRevealSlot.UNUSUAL, MarketSections.lastSlot(MarketSection.NOW))
        assertEquals(MarketRevealSlot.HALVING, MarketSections.lastSlot(MarketSection.CONTEXT))
        assertEquals(MarketRevealSlot.COIN, MarketSections.lastSlot(MarketSection.DATA))
        assertTrue(MarketSections.loading(MarketSection.NOW, setOf(MarketRevealSlot.PULSE)))
        assertFalse(MarketSections.loading(MarketSection.NOW, setOf(MarketRevealSlot.PULSE, MarketRevealSlot.UNUSUAL)))
        val all = MarketRevealSlot.entries.toSet()
        assertTrue(MarketSections.loading(MarketSection.DATA, all - MarketRevealSlot.COIN))
        assertFalse(MarketSections.loading(MarketSection.DATA, all))
        // Je Register für sich: «Daten» fertig, auch wenn «Einordnung» noch lädt
        val dataDone = all - MarketRevealSlot.HALVING
        assertFalse(MarketSections.loading(MarketSection.DATA, dataDone))
        assertTrue(MarketSections.loading(MarketSection.CONTEXT, dataDone))
    }

    @Test
    fun swipeMovesToNeighbour() {
        // Nach links wischen: nächstes Register; nach rechts: voriges; am Rand nichts
        assertEquals(MarketSection.CONTEXT, MarketSections.swipeTarget(MarketSection.NOW, -100f, 50f, rtl = false))
        assertEquals(MarketSection.NOW, MarketSections.swipeTarget(MarketSection.CONTEXT, 100f, 50f, rtl = false))
        assertNull(MarketSections.swipeTarget(MarketSection.NOW, 100f, 50f, rtl = false))
        assertNull(MarketSections.swipeTarget(MarketSection.DATA, -100f, 50f, rtl = false))
        // Zu kurz
        assertNull(MarketSections.swipeTarget(MarketSection.NOW, -40f, 50f, rtl = false))
        // Rechts-nach-links: umgekehrt
        assertEquals(MarketSection.CONTEXT, MarketSections.swipeTarget(MarketSection.NOW, 100f, 50f, rtl = true))
        assertNull(MarketSections.swipeTarget(MarketSection.NOW, -100f, 50f, rtl = true))
    }

    @Test
    fun macroHintRegister() {
        assertEquals(MarketSection.NOW, MarketSections.macroSection(imminent = true))
        assertEquals(MarketSection.DATA, MarketSections.macroSection(imminent = false))
    }

    @Test
    fun subpageFromRoute() {
        assertEquals(MarketSection.CONTEXT, MarketSections.subpage("CONTEXT"))
        assertEquals(MarketSection.DATA, MarketSections.subpage("DATA"))
        // «Jetzt» steht im Tab selbst; Unbekanntes: «Einordnung»
        assertEquals(MarketSection.CONTEXT, MarketSections.subpage("NOW"))
        assertEquals(MarketSection.CONTEXT, MarketSections.subpage(null))
        assertEquals(MarketSection.CONTEXT, MarketSections.subpage("x"))
    }
}
