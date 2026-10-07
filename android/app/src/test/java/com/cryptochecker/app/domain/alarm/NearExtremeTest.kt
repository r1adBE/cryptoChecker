package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.alarm.NearExtreme.Decision
import com.cryptochecker.app.domain.alarm.NearExtreme.Range
import com.cryptochecker.app.domain.alarm.NearExtreme.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NearExtremeTest {

    private val day = 24 * 60 * 60_000L
    private val now = 1_000 * day + 6 * 60 * 60_000L // 06:00 UTC eines Tages

    /** [count] abgeschlossene Tage bis gestern plus der laufende Tag. */
    private fun candles(count: Int, highOf: (Int) -> Double = { 100.0 + it }, lowOf: (Int) -> Double = { 50.0 + it }): List<HourCandle> {
        val today = (now / day) * day
        val done = (count downTo 1).map { back ->
            val i = count - back
            HourCandle(today - back * day, 1.0, highOf(i), lowOf(i), 1.0, 1.0)
        }
        // Laufender Tag mit Extremwerten, die nicht zählen dürfen
        return done + HourCandle(today, 1.0, 10_000.0, 0.001, 1.0, 1.0)
    }

    private fun decide(
        side: Side = Side.HIGH,
        price: Double,
        range: Range = Range(high = 100.0, low = 50.0),
        threshold: Double = 2.0,
        armed: Boolean = true,
        lastLevel: Double? = null,
        cooldown: Boolean = false,
        lastTriggeredAt: Long = 0,
        at: Long = now,
    ) = NearExtreme.decide(side, price, range, threshold, armed, lastLevel, cooldown, lastTriggeredAt, at)

    private val hour = 60 * 60_000L

    @Test
    fun windowDaysFallsBackTo30() {
        assertEquals(30, NearExtreme.windowDays(1))
        assertEquals(30, NearExtreme.windowDays(4))
        assertEquals(90, NearExtreme.windowDays(90))
        assertEquals(365, NearExtreme.windowDays(365))
    }

    @Test
    fun rangeUsesOnlyCompletedDaysOfTheWindow() {
        val list = candles(400)
        val r30 = NearExtreme.range(list, 30, now)!!
        // letzte 30 abgeschlossene Tage: i = 370…399
        assertEquals(100.0 + 399, r30.high, 1e-9)
        assertEquals(50.0 + 370, r30.low, 1e-9)
        val r365 = NearExtreme.range(list, 365, now)!!
        assertEquals(50.0 + 35, r365.low, 1e-9)
    }

    @Test
    fun rangeNeedsEnoughHistory() {
        assertNull(NearExtreme.range(candles(20), 30, now))
        assertTrue(NearExtreme.range(candles(27), 30, now) != null)
        // Coinbase liefert höchstens 300 Tage: für 1 Jahr reichen 270
        assertTrue(NearExtreme.range(candles(299), 365, now) != null)
        assertNull(NearExtreme.range(candles(200), 365, now))
        assertNull(NearExtreme.range(emptyList(), 30, now))
    }

    @Test
    fun nearHighFiresWithinDistance() {
        val d = decide(price = 98.4) as Decision.Fire
        assertFalse(d.newExtreme)
        assertEquals(1.6, d.distancePercent, 1e-9)
        assertEquals(100.0, d.extreme, 1e-9)
        assertEquals(100.0, d.level, 1e-9)
        assertEquals(Decision.None, decide(price = 97.9))
    }

    @Test
    fun exactlyAtThresholdFires() {
        assertTrue(decide(price = 98.0) is Decision.Fire)
    }

    @Test
    fun newHighFires() {
        val d = decide(price = 101.0) as Decision.Fire
        assertTrue(d.newExtreme)
        assertEquals(1.0, d.distancePercent, 1e-9)
        assertEquals(101.0, d.level, 1e-9)
    }

    @Test
    fun nearLowAndNewLow() {
        val near = decide(side = Side.LOW, price = 50.5) as Decision.Fire
        assertFalse(near.newExtreme)
        assertEquals(1.0, near.distancePercent, 1e-9)
        assertEquals(50.0, near.level, 1e-9)
        val fresh = decide(side = Side.LOW, price = 49.0) as Decision.Fire
        assertTrue(fresh.newExtreme)
        assertEquals(49.0, fresh.level, 1e-9)
        assertEquals(Decision.None, decide(side = Side.LOW, price = 52.0))
    }

    @Test
    fun reportedAlarmStaysQuietInZone() {
        assertEquals(Decision.None, decide(price = 99.0, armed = false, lastLevel = 100.0))
    }

    @Test
    fun reportedAlarmFiresAgainOnlyForClearlyFurtherHigh() {
        val last = now - 2 * hour
        // Schwelle 2 % → weiteres Hoch erst ab +1 % über der gemeldeten Marke
        assertEquals(1.0, NearExtreme.furtherStepPercent(2.0), 1e-9)
        assertEquals(0.5, NearExtreme.furtherStepPercent(0.5), 1e-9)
        // Nach «nahe am Hoch» (Marke 100): 100.5 ist neues Hoch, aber nur +0,5 %
        assertEquals(Decision.None, decide(price = 100.5, armed = false, lastLevel = 100.0, lastTriggeredAt = last))
        assertTrue(decide(price = 101.0, armed = false, lastLevel = 100.0, lastTriggeredAt = last) is Decision.Fire)
        // Nach «neues Hoch» bei 102: 101 ist zwar über dem alten Hoch, aber nicht weiter
        assertEquals(Decision.None, decide(price = 101.0, armed = false, lastLevel = 102.0, lastTriggeredAt = last))
        assertEquals(Decision.None, decide(price = 102.5, armed = false, lastLevel = 102.0, lastTriggeredAt = last))
        assertTrue(decide(price = 103.1, armed = false, lastLevel = 102.0, lastTriggeredAt = last) is Decision.Fire)
        // Tief: nur ein deutlich tieferes (−1 %)
        assertEquals(Decision.None, decide(side = Side.LOW, price = 46.8, armed = false, lastLevel = 47.0, lastTriggeredAt = last))
        assertTrue(decide(side = Side.LOW, price = 46.5, armed = false, lastLevel = 47.0, lastTriggeredAt = last) is Decision.Fire)
    }

    @Test
    fun rallyWithoutCooldownFiresAtMostHourly() {
        // Cooldown 0, wiederholend: Kurs steigt bei jeder Aktualisierung deutlich
        assertEquals(Decision.None, decide(price = 110.0, armed = false, lastLevel = 102.0, lastTriggeredAt = now - 59 * 60_000L))
        assertTrue(decide(price = 110.0, armed = false, lastLevel = 102.0, lastTriggeredAt = now - hour) is Decision.Fire)
        // Scharfer Alarm meldet ein neues Hoch sofort
        assertTrue(decide(price = 101.0, armed = true, lastTriggeredAt = now - 60_000L) is Decision.Fire)
    }

    @Test
    fun rearmAfterLeavingZoneClearly() {
        // Schwelle 2 % → wieder scharf erst ab mehr als 3 % Abstand
        assertEquals(3.0, NearExtreme.rearmDistance(2.0), 1e-9)
        assertEquals(Decision.None, decide(price = 97.5, armed = false, lastLevel = 100.0))
        assertEquals(Decision.Rearm, decide(price = 96.0, armed = false, lastLevel = 100.0))
        // Bereits scharf: nichts zu tun
        assertEquals(Decision.None, decide(price = 96.0, armed = true))
        // Kleine Schwelle: mindestens 0,5 Prozentpunkte Abstand
        assertEquals(1.0, NearExtreme.rearmDistance(0.5), 1e-9)
    }

    @Test
    fun cooldownBlocksFiring() {
        assertEquals(Decision.None, decide(price = 99.0, cooldown = true))
        assertEquals(Decision.None, decide(price = 105.0, cooldown = true))
        assertTrue(NearExtreme.inCooldown(lastTriggeredAt = 1_000, now = 1_000 + 59_000, cooldownMinutes = 1))
        assertFalse(NearExtreme.inCooldown(lastTriggeredAt = 1_000, now = 1_000 + 60_000, cooldownMinutes = 1))
        assertFalse(NearExtreme.inCooldown(lastTriggeredAt = 0, now = 5_000, cooldownMinutes = 10))
        assertFalse(NearExtreme.inCooldown(lastTriggeredAt = 1_000, now = 2_000, cooldownMinutes = 0))
    }

    @Test
    fun invalidInputsDoNothing() {
        assertEquals(Decision.None, decide(price = 0.0))
        assertEquals(Decision.None, decide(price = Double.NaN))
        assertEquals(Decision.None, decide(price = 99.0, threshold = 0.0))
        assertEquals(Decision.None, decide(price = 99.0, range = Range(high = 100.0, low = 0.0)))
    }

    @Test
    fun scaledRange() {
        val r = Range(100.0, 50.0).scaled(0.9)
        assertEquals(90.0, r.high, 1e-9)
        assertEquals(45.0, r.low, 1e-9)
    }

    @Test
    fun sentencePartsUseWindowDays() {
        val price = { v: Double, c: String -> "${v.toLong()} $c" }
        val p = AlarmSentence.parts(AlarmSentence.Kind.NEAR_HIGH, "BTC", 2.0, "USDT", 90, Locale.US, price)!!
        assertEquals("2%", p.value)
        assertEquals(90, p.windowHours)
        // Unbekannter Zeitraum (z. B. Spaltenstandard 1) → 30 Tage
        assertEquals(30, AlarmSentence.parts(AlarmSentence.Kind.NEAR_LOW, "BTC", 2.0, "USDT", 1, Locale.US, price)!!.windowHours)
        assertNull(AlarmSentence.parts(AlarmSentence.Kind.NEAR_LOW, "BTC", 0.0, "USDT", 30, Locale.US, price))
    }
}
