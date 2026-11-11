package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.market.MarketNoticeGate.FearGreedKind
import com.cryptochecker.app.domain.market.MarketNoticeGate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus MarketNoticeGateTest

/** Wie `MarketNoticeGateTests` (iOS). */
class MarketNoticeGateTest {

    @Test
    fun `gemerkt wird nur nach erfolgreichem Absetzen`() {
        assertTrue(MarketNoticeGate.storeAfterNotify(Step.STORE, posted = false))
        assertTrue(MarketNoticeGate.storeAfterNotify(Step.NOTIFY, posted = true))
        assertFalse(MarketNoticeGate.storeAfterNotify(Step.NOTIFY, posted = false))
        assertFalse(MarketNoticeGate.storeAfterNotify(Step.HOLD, posted = true))
    }

    @Test
    fun `Fear und Greed unter der Grenze`() {
        val step = MarketNoticeGate.fearGreed(15, last = 30, below = 20, above = 0, canNotify = true)
        assertEquals(Step.NOTIFY, step.step)
        assertEquals(FearGreedKind.BELOW, step.kind)
        // Schon beim letzten Lauf darunter: nicht erneut melden
        assertEquals(Step.STORE, MarketNoticeGate.fearGreed(12, last = 15, below = 20, above = 0, canNotify = true).step)
    }

    @Test
    fun `Fear und Greed ueber der Grenze und erster Wert`() {
        val step = MarketNoticeGate.fearGreed(85, last = null, below = 20, above = 80, canNotify = true)
        assertEquals(Step.NOTIFY, step.step)
        assertEquals(FearGreedKind.ABOVE, step.kind)
        val inside = MarketNoticeGate.fearGreed(50, last = null, below = 20, above = 80, canNotify = true)
        assertEquals(Step.STORE, inside.step)
        assertNull(inside.kind)
    }

    @Test
    fun `Fear und Greed ohne Zustellung bleibt offen und kommt spaeter`() {
        val held = MarketNoticeGate.fearGreed(15, last = 30, below = 20, above = 0, canNotify = false)
        assertEquals(Step.HOLD, held.step)
        assertEquals(FearGreedKind.BELOW, held.kind)
        assertFalse(MarketNoticeGate.storeAfterNotify(held.step, posted = false))
        // Alter Wert (30) blieb liegen: beim nächsten Lauf mit Zustellung wird gemeldet
        assertEquals(Step.NOTIFY, MarketNoticeGate.fearGreed(14, last = 30, below = 20, above = 0, canNotify = true).step)
    }

    @Test
    fun `Grenzen aus melden nie`() {
        assertEquals(Step.STORE, MarketNoticeGate.fearGreed(5, last = 50, below = 0, above = 0, canNotify = true).step)
    }

    @Test
    fun `geaenderte Grenze meldet schon ueberschrittenen Wert, unveraenderte nicht doppelt`() {
        // Gemerkt: 15 bei Grenze «unter 10» (nicht gemeldet). Neue Grenze 20: jetzt melden
        assertTrue(MarketNoticeGate.boundChanged(stored = 10, current = 20))
        val step = MarketNoticeGate.fearGreed(15, last = 15, below = 20, above = 0, canNotify = true, belowChanged = true)
        assertEquals(Step.NOTIFY, step.step)
        assertEquals(FearGreedKind.BELOW, step.kind)
        // Ohne Änderung: gemerkter Stand 15 schon darunter, keine Doppelmeldung
        assertFalse(MarketNoticeGate.boundChanged(stored = 20, current = 20))
        assertEquals(Step.STORE, MarketNoticeGate.fearGreed(14, last = 15, below = 20, above = 0, canNotify = true).step)
        // Nur «über» geändert: die schon gemeldete «unter»-Grenze kommt nicht erneut
        assertEquals(
            Step.STORE,
            MarketNoticeGate.fearGreed(15, last = 15, below = 20, above = 90, canNotify = true, aboveChanged = true).step,
        )
        // Keine Grenze gemerkt: als geändert werten
        assertTrue(MarketNoticeGate.boundChanged(stored = null, current = 20))
    }
}

// ---- aus MarketSectionsTest

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

// ---- aus MarketTotalsTest

class MarketTotalsTest {

    private val totals = MarketTotals(
        marketCap = mapOf("usd" to 3.45e12, "chf" to 2.8e12, "eur" to 3.0e12, "jpy" to Double.NaN),
        volume = mapOf("usd" to 1.2e11, "chf" to 9.7e10, "jpy" to 1.8e13),
        changePercent24h = 1.25,
    )

    @Test
    fun usesConversionCurrencyWhenAvailable() {
        assertEquals(MarketTotals.Values("CHF", 2.8e12, 9.7e10), totals.valuesIn("CHF"))
        assertEquals(MarketTotals.Values("CHF", 2.8e12, 9.7e10), totals.valuesIn(" chf "))
    }

    @Test
    fun fallsBackToUsd() {
        // EUR fehlt beim Volumen, JPY ist ungültig, XYZ unbekannt
        assertEquals("USD", totals.valuesIn("EUR")?.currency)
        assertEquals("USD", totals.valuesIn("JPY")?.currency)
        assertEquals("USD", totals.valuesIn("XYZ")?.currency)
        assertEquals("USD", totals.valuesIn(null)?.currency)
        assertEquals(3.45e12, totals.valuesIn("")!!.marketCap, 0.0)
    }

    @Test
    fun nothingWithoutUsd() {
        assertNull(MarketTotals(mapOf("chf" to 1.0), mapOf("chf" to 1.0), null).valuesIn("EUR"))
        assertNull(MarketTotals(emptyMap(), emptyMap(), null).valuesIn("USD"))
    }
}
