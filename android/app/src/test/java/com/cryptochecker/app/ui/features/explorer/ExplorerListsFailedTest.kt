package com.cryptochecker.app.ui.features.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerListsFailedTest {
    @Test
    fun fewFailedExchangesAreNamedAtOnce() {
        assertFalse(failedSummarized(failed = 3, expanded = false))
        assertEquals(listOf("Bybit", "kraken", "OKX") to 0, failedNamesShown(listOf("OKX", "kraken", "Bybit"), expanded = false))
    }

    @Test
    fun manyFailedExchangesStayCalmUntilTapped() {
        assertTrue(failedSummarized(failed = 4, expanded = false))
        assertFalse(failedSummarized(failed = 75, expanded = true))
        val all = (1..10).map { "E$it" }
        assertEquals(10, failedNamesShown(all, expanded = true).first.size)
    }
}
