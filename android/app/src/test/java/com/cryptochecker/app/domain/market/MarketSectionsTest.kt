package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSectionsTest {

    @Test
    fun collapsibleSectionsStartCollapsed() {
        // Auch beim allerersten Besuch: Fachbegriffe erst nach dem Aufklappen
        assertFalse(MarketSections.expanded(MarketSection.CONTEXT, sessionChoice = null))
        assertFalse(MarketSections.expanded(MarketSection.DATA, sessionChoice = null))
    }

    @Test
    fun sessionChoiceWins() {
        assertTrue(MarketSections.expanded(MarketSection.CONTEXT, sessionChoice = true))
        assertTrue(MarketSections.expanded(MarketSection.DATA, sessionChoice = true))
        assertFalse(MarketSections.expanded(MarketSection.DATA, sessionChoice = false))
    }

    @Test
    fun nowNeverCollapses() {
        assertFalse(MarketSection.NOW.collapsible)
        assertTrue(MarketSection.CONTEXT.collapsible)
        assertTrue(MarketSection.DATA.collapsible)
        for (choice in listOf(null, true, false)) {
            assertTrue(MarketSections.expanded(MarketSection.NOW, choice))
        }
    }

    @Test
    fun summaryJoinsPresentParts() {
        assertEquals("Gier 72 · Neutral", MarketSections.summary(listOf("Gier 72", "Neutral")))
        assertEquals("Neutral", MarketSections.summary(listOf(null, " Neutral ")))
        assertEquals("Gier 72", MarketSections.summary(listOf("Gier 72", "", "  ")))
        assertEquals("", MarketSections.summary(listOf(null, null)))
        assertEquals("", MarketSections.summary(emptyList()))
    }
}
