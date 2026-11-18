package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coins als Liste im grossen Widget «Was gerade auffällt» ([PulseWidgetList]); Gegenstück iOS `PulseWidgetHome`. */
class PulseWidgetListTest {

    @Test
    fun `extra height - top, header and rows minus the chip row, rows at least the minimum`() {
        // 8 + 15 + 3 × 22 − 20
        assertEquals(69f, PulseWidgetList.extraDp(3, rowTextDp = 17f, headerDp = 15f), 0.001f)
        // grosse Schrift: gemessene Zeile zählt
        assertEquals(8f + 18f + 3 * 26f - 20f, PulseWidgetList.extraDp(3, rowTextDp = 26f, headerDp = 18f), 0.001f)
    }

    @Test
    fun `list only large, with coins and enough room below the block`() {
        assertTrue(PulseWidgetList.fits(large = true, freeDp = 69f, coins = 3, rowTextDp = 17f, headerDp = 15f))
        assertFalse(PulseWidgetList.fits(large = true, freeDp = 68.9f, coins = 3, rowTextDp = 17f, headerDp = 15f))
        assertFalse(PulseWidgetList.fits(large = false, freeDp = 500f, coins = 3, rowTextDp = 17f, headerDp = 15f))
        assertFalse(PulseWidgetList.fits(large = true, freeDp = 500f, coins = 0, rowTextDp = 17f, headerDp = 15f))
    }

    @Test
    fun `block height matches the sentence count`() {
        val heights = listOf(30f, 30f, 30f)
        assertEquals(0f, PulseWidgetPlain.blockUsedDp(16f, 30f, heights, 0), 0.001f)
        // 8 + 16 + 4 + 30 + 2 × (4 + 30)
        assertEquals(126f, PulseWidgetPlain.blockUsedDp(16f, 30f, heights, 2), 0.001f)
        assertEquals(2, PulseWidgetPlain.sentenceCount(126f, 16f, 30f, heights))
        assertEquals(1, PulseWidgetPlain.sentenceCount(125.9f, 16f, 30f, heights))
    }
}
