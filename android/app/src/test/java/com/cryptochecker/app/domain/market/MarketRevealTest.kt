package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.market.MarketReveal.STAGGER_MILLIS
import com.cryptochecker.app.domain.market.MarketReveal.WAIT_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketRevealTest {

    private val start = 10_000L
    private val n = MarketReveal.COUNT

    /** Zeitpunkte, zu denen die Teile nacheinander erscheinen (bis alle stehen). */
    private fun revealTimes(readyAt: List<Long?>): List<Long> {
        val times = mutableListOf<Long>()
        var now = start
        var shown = 0
        while (true) {
            val plan = MarketReveal.plan(readyAt, start, now)
            repeat(plan.revealed - shown) { times += now }
            shown = plan.revealed
            now = plan.nextAt ?: return times
        }
    }

    @Test
    fun elevenSlotsInTabOrder() {
        assertEquals(11, n)
        assertEquals(0, MarketRevealSlot.PULSE.ordinal)
        // «Heute auffällig» direkt unter dem Pulse, vor Fear & Greed
        assertEquals(1, MarketRevealSlot.UNUSUAL.ordinal)
        assertEquals(2, MarketRevealSlot.FEAR_GREED.ordinal)
        assertEquals(4, MarketRevealSlot.HEADER_CONTEXT.ordinal)
        assertEquals(10, MarketRevealSlot.GAS.ordinal)
    }

    @Test
    fun allCachedAtStart_allAtOnceWithoutAnimation() {
        val ready = List<Long?>(n) { start }
        assertEquals(MarketReveal.Plan(n, null), MarketReveal.plan(ready, start, start))
        assertEquals(n, MarketReveal.instantCount(ready, start))
    }

    @Test
    fun nothingReady_nothingShownUntilWait() {
        val ready = List<Long?>(n) { null }
        assertEquals(0, MarketReveal.instantCount(ready, start))
        assertEquals(MarketReveal.Plan(0, start + WAIT_MILLIS), MarketReveal.plan(ready, start, start))
        assertEquals(MarketReveal.Plan(0, start + WAIT_MILLIS), MarketReveal.plan(ready, start, start + 1_000))
        // Frist vorbei: Pulse als Platzhalter, die nächste wartet wieder höchstens 1,5 s
        assertEquals(
            MarketReveal.Plan(1, start + 2 * WAIT_MILLIS),
            MarketReveal.plan(ready, start, start + WAIT_MILLIS)
        )
    }

    @Test
    fun allReadyShortlyAfterStart_staggered() {
        val ready = List<Long?>(n) { start + 5 }
        val times = revealTimes(ready)
        assertEquals(n, times.size)
        assertEquals(start + 5, times[0])
        for (i in 1 until n) assertEquals(STAGGER_MILLIS, times[i] - times[i - 1])
    }

    @Test
    fun cardWaitsForPreviousEvenIfReadyEarlier() {
        // Gas ist sofort da, Pulse erst nach 300 ms: Gas kommt trotzdem zuletzt
        val ready = MutableList<Long?>(n) { start + 300 }
        ready[MarketRevealSlot.GAS.ordinal] = start + 1
        val times = revealTimes(ready)
        assertEquals(start + 300, times[0])
        assertEquals(start + 300 + 10 * STAGGER_MILLIS, times[MarketRevealSlot.GAS.ordinal])
    }

    @Test
    fun slowSourceDoesNotBlockTheRest() {
        // Marktphase kommt nie: erscheint 1,5 s nach dem Abschnittstitel als Platzhalter
        val ready = MutableList<Long?>(n) { start + 10 }
        ready[MarketRevealSlot.PHASE.ordinal] = null
        val times = revealTimes(ready)
        val header = times[MarketRevealSlot.HEADER_CONTEXT.ordinal]
        assertEquals(header + WAIT_MILLIS, times[MarketRevealSlot.PHASE.ordinal])
        assertEquals(
            header + WAIT_MILLIS + STAGGER_MILLIS,
            times[MarketRevealSlot.DOMINANCE.ordinal]
        )
        assertEquals(n, times.size)
    }

    @Test
    fun lateDataAfterDeadline_countsAsTimeout() {
        val ready = MutableList<Long?>(n) { start }
        ready[MarketRevealSlot.FEAR_GREED.ordinal] = start + 5_000
        // Pulse und «Heute auffällig» sofort, Fear & Greed nach 1,5 s als Platzhalter — nicht erst nach 5 s
        val plan = MarketReveal.plan(ready, start, start + WAIT_MILLIS)
        assertEquals(3, plan.revealed)
        assertEquals(2, MarketReveal.instantCount(ready, start))
    }

    @Test
    fun slowUnusualCard_doesNotHoldBackFearGreed() {
        // «Heute auffällig» kommt nie: Platzhalter 1,5 s nach dem Pulse, Fear & Greed 60 ms danach
        val ready = MutableList<Long?>(n) { start + 10 }
        ready[MarketRevealSlot.UNUSUAL.ordinal] = null
        val times = revealTimes(ready)
        val pulse = times[MarketRevealSlot.PULSE.ordinal]
        assertEquals(pulse + WAIT_MILLIS, times[MarketRevealSlot.UNUSUAL.ordinal])
        assertEquals(pulse + WAIT_MILLIS + STAGGER_MILLIS, times[MarketRevealSlot.FEAR_GREED.ordinal])
    }

    @Test
    fun cachedPrefixInstant_restFollows() {
        val ready = MutableList<Long?>(n) { start }
        ready[MarketRevealSlot.HALVING.ordinal] = start + 800
        assertEquals(MarketRevealSlot.HALVING.ordinal, MarketReveal.instantCount(ready, start))
        // Beim Start ist der Verlauf noch nicht da: spätestens zur Frist neu rechnen
        val pending = ready.toMutableList().also { it[MarketRevealSlot.HALVING.ordinal] = null }
        val atStart = MarketReveal.plan(pending, start, start)
        assertEquals(MarketRevealSlot.HALVING.ordinal, atStart.revealed)
        assertEquals(start + WAIT_MILLIS, atStart.nextAt)
        val times = revealTimes(ready)
        assertEquals(start + 800, times[MarketRevealSlot.HALVING.ordinal])
        assertEquals(start + 800 + STAGGER_MILLIS, times[MarketRevealSlot.HEADER_DATA.ordinal])
    }

    @Test
    fun readyExactlyAtDeadline_isShownWithData() {
        val ready = MutableList<Long?>(n) { null }
        ready[0] = start + WAIT_MILLIS
        val plan = MarketReveal.plan(ready, start, start + WAIT_MILLIS)
        assertEquals(1, plan.revealed)
        assertEquals(start + 2 * WAIT_MILLIS, plan.nextAt)
    }

    @Test
    fun neverMoreThanOneStepAheadOfNow() {
        val ready = List<Long?>(n) { start }
        // Nichts beim Start, alles 1 ms danach: Abstand bleibt 60 ms
        val later = List<Long?>(n) { start + 1 }
        assertEquals(n, MarketReveal.plan(ready, start, start).revealed)
        val plan = MarketReveal.plan(later, start, start + 1)
        assertEquals(1, plan.revealed)
        assertEquals(start + 1 + STAGGER_MILLIS, plan.nextAt)
        assertNull(MarketReveal.plan(later, start, start + 1 + 10 * STAGGER_MILLIS).nextAt)
    }
}
