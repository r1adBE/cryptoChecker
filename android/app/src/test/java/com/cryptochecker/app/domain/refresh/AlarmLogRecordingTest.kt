package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmFreshness
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.app.domain.watch.DexLiquidity
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alarm-Protokoll an den echten Entscheidungsstellen ([RefreshResultProcessor] mit [RefreshEffects]): je Prüfung das
 * richtige Ergebnis — und die Entscheidung selbst bleibt dieselbe (Meldungen wie ohne Protokoll).
 */
class AlarmLogRecordingTest {

    private fun ticker(price: Double, exchangeTime: Long? = null, markFallback: Boolean = false) = SimpleTicker().apply {
        last = price
        timestamp = System.currentTimeMillis()
        this.exchangeTime = exchangeTime
        this.markFallback = markFallback
    }

    private fun RefreshFixture.process(fetched: Map<Long, Fetched>): RefreshResultProcessor.Processed = runBlocking {
        val watches: List<WatchEntity> = store.getWatches()
        lock.withLock { proof -> processor.process(proof, watches, fetched, settings.settings, dayStart = null) }
    }

    private fun RefreshFixture.last(alarmId: Long): AlarmLogEntry = alarmLog.last { it.alarmId == alarmId }

    @Test
    fun firedAlarmIsNotable() {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        assertEquals(1, f.process(mapOf(1L to Fetched(ticker(101.0), null))).alarms)

        val e = f.last(7)
        assertEquals(AlarmLogOutcome.FIRED, e.outcome)
        assertTrue(e.notable)
        assertEquals(101.0, e.price!!, 0.0)
        assertEquals("BTC/USDT", e.pair)
        assertEquals("Binance", e.exchange)
    }

    @Test
    fun notReachedThenAlreadyTriggered() {
        val f = RefreshFixture(listOf(watch(price = 90.0)), listOf(above(100.0)))
        f.process(mapOf(1L to Fetched(ticker(95.0), null)))
        assertEquals(AlarmLogOutcome.NOT_REACHED, f.last(7).outcome)
        assertFalse(f.last(7).notable)

        f.process(mapOf(1L to Fetched(ticker(101.0), null)))
        assertEquals(AlarmLogOutcome.FIRED, f.last(7).outcome)
        // Einmaliger Alarm ist jetzt aus (nicht mehr geprüft); wiederholender wartet auf die Rückkehr
        val g = RefreshFixture(listOf(watch(price = 90.0)), listOf(above(100.0, repeating = true)))
        g.process(mapOf(1L to Fetched(ticker(101.0), null)))
        g.process(mapOf(1L to Fetched(ticker(102.0), null)))
        // Ruhezeit 15 Min. (Fixture): erfüllt wäre die Marke nicht mehr (schon gemeldet) — also «schon gemeldet»
        assertEquals(AlarmLogOutcome.ALREADY_TRIGGERED, g.last(7).outcome)
        assertFalse(g.last(7).notable)
    }

    @Test
    fun percentAlarmInCooldownIsNotable() {
        val alarm = AlarmEntity(
            id = 5, watchId = 1, condition = AlarmCondition.CHANGE_PERCENT_UP, threshold = 5.0, repeating = true,
            referencePrice = 100.0, lastTriggeredAt = System.currentTimeMillis() - 60_000L,
        )
        val f = RefreshFixture(listOf(watch(price = 100.0)), listOf(alarm))
        assertEquals(0, f.process(mapOf(1L to Fetched(ticker(110.0), null))).alarms)
        assertEquals(AlarmLogOutcome.COOLDOWN, f.last(5).outcome)
        assertTrue(f.last(5).notable)
    }

    @Test
    fun staleAndMarkPriceAreRecordedWithoutChangingTheDecision() {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        val old = System.currentTimeMillis() - AlarmFreshness.EXCHANGE_STALE_MILLIS - 60_000L
        assertEquals(0, f.process(mapOf(1L to Fetched(ticker(101.0, exchangeTime = old), null))).alarms)
        assertEquals(AlarmLogOutcome.STALE, f.last(7).outcome)
        assertTrue("wäre fällig gewesen", f.last(7).notable)
        assertEquals(old, f.last(7).exchangeTime)
        assertEquals(0L, f.store.alarm(7).referenceAt)

        val g = RefreshFixture(listOf(watch(price = 99.0, marketKey = "Hyperliquid")), listOf(above(100.0)))
        assertEquals(0, g.process(mapOf(1L to Fetched(ticker(101.0, markFallback = true), null))).alarms)
        assertEquals(AlarmLogOutcome.MARK_PRICE, g.last(7).outcome)
        assertTrue(g.last(7).notable)

        // Nicht erreicht trotz Sperre: nur letzte Prüfung, nicht ins Protokoll
        val h = RefreshFixture(listOf(watch(price = 90.0)), listOf(above(100.0)))
        h.process(mapOf(1L to Fetched(ticker(95.0, exchangeTime = old), null)))
        assertEquals(AlarmLogOutcome.STALE, h.last(7).outcome)
        assertFalse(h.last(7).notable)
    }

    @Test
    fun lowLiquidityIsRecorded() {
        val pool = "solana/pool1"
        val f = RefreshFixture(listOf(watch(price = 99.0, marketKey = DexLiquidity.MARKET_KEY, pairId = pool)), listOf(above(100.0)))
        f.dex.blocked = setOf(pool)
        assertEquals(0, f.process(mapOf(1L to Fetched(ticker(101.0), null))).alarms)
        assertEquals(AlarmLogOutcome.LOW_LIQUIDITY, f.last(7).outcome)
        assertTrue(f.last(7).notable)
    }

    @Test
    fun notificationsOffAndNotShown() {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        f.notifier.deliverable = false
        assertEquals(0, f.process(mapOf(1L to Fetched(ticker(101.0), null))).alarms)
        assertEquals(AlarmLogOutcome.NOTIFICATIONS_OFF, f.last(7).outcome)
        assertTrue(f.last(7).notable)

        val g = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        g.notifier.shows = false
        assertEquals(0, g.process(mapOf(1L to Fetched(ticker(101.0), null))).alarms)
        assertEquals(AlarmLogOutcome.NOT_SHOWN, g.last(7).outcome)
        // Gleich danach: unbestätigter Versuch, Neuversuch erst nach der Abklingzeit
        g.process(mapOf(1L to Fetched(ticker(101.5), null)))
        assertEquals(AlarmLogOutcome.COOLDOWN, g.last(7).outcome)
    }

    @Test
    fun quietHoursHoldAndAreRecorded() {
        val minute = QuietHours.minuteOfDay()
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        f.settings.settings = f.settings.settings.copy(
            quietHoursEnabled = true,
            quietHoursStart = minute,
            quietHoursEnd = (minute + 3) % QuietHours.MINUTES_PER_DAY,
        )
        assertEquals(0, f.process(mapOf(1L to Fetched(ticker(101.0), null))).alarms)
        assertEquals(AlarmLogOutcome.QUIET_HOURS, f.last(7).outcome)
        assertTrue(f.store.alarm(7).enabled)
    }

    @Test
    fun noPriceAndConversionWithoutRate() {
        val usd = AlarmEntity(id = 9, watchId = 2, condition = AlarmCondition.PRICE_ABOVE, threshold = 50.0, currency = "JPY")
        val f = RefreshFixture(listOf(watch(id = 1, price = 99.0), watch(id = 2, price = 99.0)), listOf(above(100.0), usd))
        f.process(mapOf(1L to Fetched(null, "HttpCode: 500"), 2L to Fetched(ticker(101.0), null)))
        assertEquals(AlarmLogOutcome.NO_PRICE, f.last(7).outcome)
        assertNull(f.last(7).price)
        assertEquals(AlarmLogOutcome.NO_RATE, f.last(9).outcome)
    }

    @Test
    fun otherAlarmKindsAreNotRecorded() {
        val volume = AlarmEntity(id = 4, watchId = 1, condition = AlarmCondition.VOLUME_SPIKE, threshold = 3.0)
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(volume))
        f.process(mapOf(1L to Fetched(ticker(101.0), null)))
        assertTrue(f.alarmLog.isEmpty())
    }
}
