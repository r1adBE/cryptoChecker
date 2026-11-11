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

    // ---------------- Deaktivierte Paare ----------------

    /** Paar wie in der Merkliste: Name, deaktiviert, nicht mehr gehandelt, letzte Aktualisierung. */
    private data class Pair4(val name: String, val disabled: Boolean, val notTraded: Boolean, val lastUpdate: Long)

    private val now = 1_000_000_000L
    private val staleAfter = 600_000L
    private val pairs = listOf(
        Pair4("BTC", disabled = false, notTraded = false, lastUpdate = now - 60_000),      // aktuell
        Pair4("ETH", disabled = false, notTraded = false, lastUpdate = now - 3_600_000),   // veraltet
        Pair4("SOL", disabled = true, notTraded = false, lastUpdate = now - 86_400_000),   // deaktiviert, alter Kurs
        Pair4("XRP", disabled = true, notTraded = false, lastUpdate = 0),                  // deaktiviert, nie abgefragt
        Pair4("LUNA", disabled = false, notTraded = true, lastUpdate = now - 86_400_000),  // nicht mehr gehandelt
    )

    private fun stale(p: Pair4) = DisabledView.isStale(p.disabled, p.notTraded, p.lastUpdate, now, staleAfter)

    @Test
    fun disabledNeverCountsAsStale() {
        assertEquals(listOf("ETH"), pairs.filter(::stale).map { it.name })
        // Auch ohne je einen Kurs: deaktiviert ist nie «veraltet»
        assertEquals(false, DisabledView.isStale(disabled = true, notTraded = false, lastUpdate = 0, now = now, staleAfter = staleAfter))
        assertEquals(true, DisabledView.isStale(disabled = false, notTraded = false, lastUpdate = 0, now = now, staleAfter = staleAfter))
    }

    @Test
    fun statusCountsOnlyActivePairs() {
        // Wie WatchlistStatus.of: erst die deaktivierten weg, dann die nicht gehandelten
        val traded = DisabledView.active(pairs) { it.disabled }.filterNot { it.notTraded }
        assertEquals(listOf("BTC", "ETH"), traded.map { it.name })
        assertEquals(1, traded.count(::stale))          // «1 von 2 veraltet», nicht «3 von 4»
        // Nur noch das aktuelle Paar aktiv → «Alle Kurse aktuell», trotz alter deaktivierter Kurse
        val fresh = pairs.filter { it.name != "ETH" }
        assertEquals(0, DisabledView.active(fresh) { it.disabled }.filterNot { it.notTraded }.count(::stale))
    }

    @Test
    fun disabledViewShowsOnlyDisabledInListOrder() {
        assertEquals(2, DisabledView.count(pairs) { it.disabled })
        assertEquals(listOf("SOL", "XRP"), DisabledView.only(pairs) { it.disabled }.map { it.name })
        // Ohne deaktivierte: keine Kopie, gleiche Liste (Ansicht endet von selbst, Hinweis verschwindet)
        val none = pairs.map { it.copy(disabled = false) }
        assertEquals(0, DisabledView.count(none) { it.disabled })
        assertEquals(true, DisabledView.only(none) { it.disabled }.isEmpty())
        assertEquals(true, DisabledView.active(none) { it.disabled } === none)
    }

    @Test
    fun quickViewHasDisabledEntry() {
        assertEquals(listOf(QuickView.ACTIVITY, QuickView.STALE, QuickView.DISABLED), QuickView.entries.toList())
    }
}
