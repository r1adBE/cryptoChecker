package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickViewTest {

    // (Paar, Coin) — nach Stärke sortiert wie im ⚡-Chip
    private val hot = listOf("BTC-B" to "BTC", "SOL-B" to "sol", "BTC-K" to "btc", "PEPE-B" to "PEPE", "ETH-B" to "ETH")

    @Test
    fun withoutLimitAllPairs() {
        assertEquals(hot, ActivityView.pick(hot, { it.second }, null))
    }

    @Test
    fun limitCountsCoinsNotPairs() {
        // Zwei stärkste Coins: BTC (an zwei Börsen) und SOL — Gross/Klein egal
        assertEquals(
            listOf("BTC-B", "SOL-B", "BTC-K"),
            ActivityView.pick(hot, { it.second }, 2).map { it.first }
        )
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(emptyList<Pair<String, String>>(), ActivityView.pick(emptyList<Pair<String, String>>(), { it.second }, 3))
    }
}
