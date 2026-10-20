package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.market.MarketNoticeGate.FearGreedKind
import com.cryptochecker.app.domain.market.MarketNoticeGate.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // Stand vor dem Update (keine Grenze gemerkt): als unverändert werten
        assertFalse(MarketNoticeGate.boundChanged(stored = null, current = 20))
    }
}
