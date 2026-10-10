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
        assertTrue(MarketSections.loading(MarketSection.NOW, revealed = 1))
        assertFalse(MarketSections.loading(MarketSection.NOW, revealed = 2))
        assertTrue(MarketSections.loading(MarketSection.DATA, revealed = MarketReveal.COUNT - 1))
        assertFalse(MarketSections.loading(MarketSection.DATA, revealed = MarketReveal.COUNT))
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
}
