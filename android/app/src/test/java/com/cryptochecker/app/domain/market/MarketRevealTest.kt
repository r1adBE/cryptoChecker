package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.market.MarketReveal.STAGGER_MILLIS
import com.cryptochecker.app.domain.market.MarketReveal.WAIT_MILLIS
import com.cryptochecker.app.domain.market.MarketRevealSlot.COIN
import com.cryptochecker.app.domain.market.MarketRevealSlot.DOMINANCE
import com.cryptochecker.app.domain.market.MarketRevealSlot.FEAR_GREED
import com.cryptochecker.app.domain.market.MarketRevealSlot.GAS
import com.cryptochecker.app.domain.market.MarketRevealSlot.HALVING
import com.cryptochecker.app.domain.market.MarketRevealSlot.HEADER_CONTEXT
import com.cryptochecker.app.domain.market.MarketRevealSlot.HEADER_DATA
import com.cryptochecker.app.domain.market.MarketRevealSlot.MARKET_TOTALS
import com.cryptochecker.app.domain.market.MarketRevealSlot.PHASE
import com.cryptochecker.app.domain.market.MarketRevealSlot.PULSE
import com.cryptochecker.app.domain.market.MarketRevealSlot.UNUSUAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Je Register eine Kette (Jetzt, Einordnung, Daten). Wie iOS `CycleRevealTests`. */
class MarketRevealTest {

    private val start = 10_000L
    private val n = MarketReveal.COUNT
    private val all = MarketRevealSlot.entries.toSet()

    /** Zeitpunkt, zu dem jeder Teil erscheint (bis alle stehen). */
    private fun revealTimes(readyAt: List<Long?>): Map<MarketRevealSlot, Long> {
        val times = mutableMapOf<MarketRevealSlot, Long>()
        var now = start
        while (true) {
            val plan = MarketReveal.plan(readyAt, start, now)
            for (slot in plan.revealed) times.putIfAbsent(slot, now)
            // Einmal sichtbar, bleibt sichtbar
            assertTrue(plan.revealed.containsAll(times.keys))
            now = plan.nextAt ?: return times
        }
    }

    /** Überschriften-Teile (zeigen nichts) sind immer sofort bereit, wie im ViewModel. */
    private fun ready(default: Long?): MutableList<Long?> =
        MutableList(n) { i -> if (i == HEADER_CONTEXT.ordinal || i == HEADER_DATA.ordinal) start else default }

    @Test
    fun elevenSlotsInTabOrder() {
        assertEquals(11, n)
        assertEquals(0, PULSE.ordinal)
        // «Heute auffällig» direkt unter dem Pulse; danach «Einordnung» mit Fear & Greed zuerst
        assertEquals(1, UNUSUAL.ordinal)
        assertEquals(2, HEADER_CONTEXT.ordinal)
        assertEquals(3, FEAR_GREED.ordinal)
        // «Krypto-Markt», Gas und Coin unter «Daten»
        assertEquals(7, HEADER_DATA.ordinal)
        assertEquals(8, MARKET_TOTALS.ordinal)
        assertEquals(9, GAS.ordinal)
        assertEquals(10, COIN.ordinal)
    }

    @Test
    fun allCachedAtStart_allAtOnceWithoutAnimation() {
        val ready = List<Long?>(n) { start }
        assertEquals(MarketReveal.Plan(all, null), MarketReveal.plan(ready, start, start))
        assertEquals(all, MarketReveal.instantSlots(ready, start))
    }

    @Test
    fun nothingReady_eachRegisterWaitsForItsOwnFirst() {
        val ready = List<Long?>(n) { null }
        assertEquals(emptySet<MarketRevealSlot>(), MarketReveal.instantSlots(ready, start))
        assertEquals(MarketReveal.Plan(emptySet(), start + WAIT_MILLIS), MarketReveal.plan(ready, start, start))
        assertEquals(MarketReveal.Plan(emptySet(), start + WAIT_MILLIS), MarketReveal.plan(ready, start, start + 1_000))
        // Frist vorbei: der erste Teil jedes Registers als Platzhalter, der nächste wartet wieder höchstens 1,5 s
        assertEquals(
            MarketReveal.Plan(setOf(PULSE, HEADER_CONTEXT, HEADER_DATA), start + 2 * WAIT_MILLIS),
            MarketReveal.plan(ready, start, start + WAIT_MILLIS)
        )
    }

    @Test
    fun allReadyShortlyAfterStart_staggeredWithinEachRegister() {
        val ready = List<Long?>(n) { start + 5 }
        val times = revealTimes(ready)
        assertEquals(all, times.keys)
        for (section in MarketSection.entries) {
            val slots = MarketRevealSlot.entries.filter { MarketSections.of(it) == section }
            assertEquals(start + 5, times[slots.first()])
            for (i in 1 until slots.size) assertEquals(STAGGER_MILLIS, times.getValue(slots[i]) - times.getValue(slots[i - 1]))
        }
    }

    @Test
    fun cardWaitsForPreviousInItsRegisterEvenIfReadyEarlier() {
        // Coin ist sofort da, «Krypto-Markt» erst nach 300 ms: Coin kommt trotzdem zuletzt in «Daten»
        val ready = ready(start + 300)
        ready[COIN.ordinal] = start + 1
        val times = revealTimes(ready)
        assertEquals(start + 300, times[MARKET_TOTALS])
        assertEquals(start + 300 + 2 * STAGGER_MILLIS, times[COIN])
    }

    @Test
    fun slowSourceDoesNotBlockTheRestOfItsRegister() {
        // Marktphase kommt nie: erscheint 1,5 s nach Fear & Greed (Zeile darüber) als Platzhalter
        val ready = ready(start + 10)
        ready[PHASE.ordinal] = null
        val times = revealTimes(ready)
        val above = times.getValue(FEAR_GREED)
        assertEquals(above + WAIT_MILLIS, times[PHASE])
        assertEquals(above + WAIT_MILLIS + STAGGER_MILLIS, times[DOMINANCE])
        assertEquals(all, times.keys)
    }

    @Test
    fun slowContextSource_doesNotDelayDataRegister() {
        // Zyklus/Halving und Marktphase kommen nie: «Daten» erscheint trotzdem gleich
        val ready = ready(start + 10)
        ready[PHASE.ordinal] = null
        ready[HALVING.ordinal] = null
        val times = revealTimes(ready)
        assertEquals(start + STAGGER_MILLIS, times[MARKET_TOTALS])
        assertEquals(start + 2 * STAGGER_MILLIS, times[GAS])
        assertEquals(start + 3 * STAGGER_MILLIS, times[COIN])
        // «Einordnung» wartet je Zeile höchstens 1,5 s
        assertEquals(times.getValue(FEAR_GREED) + WAIT_MILLIS, times[PHASE])
        assertEquals(times.getValue(DOMINANCE) + WAIT_MILLIS, times[HALVING])
        // Während «Einordnung» wartet, ist «Daten» schon ganz da
        val plan = MarketReveal.plan(ready, start, start + 500)
        assertTrue(plan.revealed.containsAll(listOf(MARKET_TOTALS, GAS, COIN)))
        assertFalse(PHASE in plan.revealed)
        assertFalse(MarketSections.loading(MarketSection.DATA, plan.revealed))
        assertTrue(MarketSections.loading(MarketSection.CONTEXT, plan.revealed))
    }

    @Test
    fun slowNowRegister_doesNotDelayContext() {
        val ready = ready(start + 10)
        ready[PULSE.ordinal] = null
        val times = revealTimes(ready)
        assertEquals(start + STAGGER_MILLIS, times[FEAR_GREED])
        assertEquals(start + WAIT_MILLIS, times[PULSE])
    }

    @Test
    fun lateDataAfterDeadline_countsAsTimeout() {
        val ready = List<Long?>(n) { start }.toMutableList()
        ready[FEAR_GREED.ordinal] = start + 5_000
        // Fear & Greed nach 1,5 s als Platzhalter — nicht erst nach 5 s; «Jetzt» und «Daten» sofort
        val plan = MarketReveal.plan(ready, start, start + WAIT_MILLIS)
        assertEquals(all - setOf(PHASE, DOMINANCE, HALVING), plan.revealed)
        assertEquals(start + WAIT_MILLIS + STAGGER_MILLIS, plan.nextAt)
        assertEquals(all - setOf(FEAR_GREED, PHASE, DOMINANCE, HALVING), MarketReveal.instantSlots(ready, start))
    }

    @Test
    fun fearGreedOnNow_waitsForTheCardsAboveIt() {
        // «Heute auffällig» kommt nie: Fear & Greed steht in «Einordnung» nach 60 ms, auf «Jetzt»
        // aber erst unter «Heute auffällig» (Platzhalter 1,5 s nach dem Pulse)
        val ready = ready(start + 10)
        ready[UNUSUAL.ordinal] = null
        val times = revealTimes(ready)
        val pulse = times.getValue(PULSE)
        assertEquals(pulse + WAIT_MILLIS, times[UNUSUAL])
        assertEquals(start + STAGGER_MILLIS, times[FEAR_GREED])
        val early = MarketReveal.State(MarketReveal.plan(ready, start, start + 100).revealed)
        assertTrue(early.isShown(FEAR_GREED))
        assertFalse(early.isShownOnNow(FEAR_GREED))
        assertTrue(early.isShownOnNow(PULSE))
        val later = MarketReveal.State(MarketReveal.plan(ready, start, pulse + WAIT_MILLIS).revealed)
        assertTrue(later.isShownOnNow(FEAR_GREED))
    }

    @Test
    fun cachedPrefixInstant_restFollows() {
        val ready = List<Long?>(n) { start }.toMutableList()
        ready[HALVING.ordinal] = start + 800
        assertEquals(all - HALVING, MarketReveal.instantSlots(ready, start))
        // Beim Start ist der Verlauf noch nicht da: spätestens zur Frist neu rechnen
        val pending = ready.toMutableList().also { it[HALVING.ordinal] = null }
        val atStart = MarketReveal.plan(pending, start, start)
        assertEquals(all - HALVING, atStart.revealed)
        assertEquals(start + WAIT_MILLIS, atStart.nextAt)
        val times = revealTimes(ready)
        assertEquals(start + 800, times[HALVING])
        assertEquals(start, times[COIN])
    }

    @Test
    fun readyExactlyAtDeadline_isShownWithData() {
        val ready = MutableList<Long?>(n) { null }
        ready[0] = start + WAIT_MILLIS
        val plan = MarketReveal.plan(ready, start, start + WAIT_MILLIS)
        assertEquals(setOf(PULSE, HEADER_CONTEXT, HEADER_DATA), plan.revealed)
        assertEquals(start + 2 * WAIT_MILLIS, plan.nextAt)
    }

    @Test
    fun neverMoreThanOneStepAheadOfNow() {
        val ready = List<Long?>(n) { start }
        // Nichts beim Start, alles 1 ms danach: je Register der erste, Abstand bleibt 60 ms
        val later = List<Long?>(n) { start + 1 }
        assertEquals(all, MarketReveal.plan(ready, start, start).revealed)
        val plan = MarketReveal.plan(later, start, start + 1)
        assertEquals(setOf(PULSE, HEADER_CONTEXT, HEADER_DATA), plan.revealed)
        assertEquals(start + 1 + STAGGER_MILLIS, plan.nextAt)
        assertNull(MarketReveal.plan(later, start, start + 1 + 4 * STAGGER_MILLIS).nextAt)
    }

    @Test
    fun stateHelpers() {
        val state = MarketReveal.State(shown = setOf(PULSE, UNUSUAL, HEADER_CONTEXT, FEAR_GREED))
        assertTrue(state.isShownOnNow(FEAR_GREED))
        assertFalse(state.complete)
        assertTrue(MarketReveal.State(shown = all).complete)
        assertFalse(MarketReveal.State().isShown(PULSE))
    }
}
