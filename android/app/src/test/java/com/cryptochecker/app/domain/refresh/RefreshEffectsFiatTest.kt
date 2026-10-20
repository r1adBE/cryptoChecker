package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.NOW
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fiat-Alarme an [RefreshEffects.checkAlarms]: Devisenkurs zu alt bzw. USDT/USD unsicher → diesmal nicht prüfen (bleibt
 * scharf, Grund im Protokoll); «USDT = 1 angenommen» am Alarmtext; Volumen-Spike aus fremden Kerzen nennt die Börse.
 */
class RefreshEffectsFiatTest {

    private val minute = 60_000L

    private fun RefreshFixture.check(price: Double, previous: Double?, at: Long): Int = runBlocking {
        effects.checkAlarms(store.watches.getValue(1), price, previous, store.getAllEnabledAlarms(), settings.settings, at)
    }

    private fun ticker(price: Double) = SimpleTicker().apply {
        last = price
        timestamp = System.currentTimeMillis()
    }

    private fun RefreshFixture.process(price: Double) = runBlocking {
        val watches: List<WatchEntity> = store.getWatches()
        lock.withLock { proof ->
            processor.process(proof, watches, mapOf(1L to Fetched(ticker(price), null)), settings.settings, dayStart = null)
        }
    }

    private val chf = above(90.0).copy(currency = "CHF")

    @Test
    fun staleFxRateSkipsFiatAlarmAndKeepsItArmed() {
        val f = RefreshFixture(listOf(watch()), listOf(chf))
        f.rates.skip = FiatAlarmGuard.Skip.FX_STALE

        assertEquals(0, f.check(101.0, 99.0, NOW))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(AlarmCheckStatus.Reason.NO_RATE, f.checkStatus.state.value[7L]?.reason)
        assertEquals("Alarm unverändert scharf", chf, f.store.alarm(7))

        // Devisenkurs wieder frisch: meldet beim nächsten Durchlauf
        f.rates.skip = null
        assertEquals(1, f.check(101.0, 99.0, NOW + minute))
        assertNull(f.checkStatus.state.value[7L])
    }

    @Test
    fun skipReasonsAreLoggedAndNotableWhenDue() {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(chf))
        f.rates.skip = FiatAlarmGuard.Skip.UNSURE
        f.process(101.0)
        val entry = f.alarmLog.last { it.alarmId == 7L }
        assertEquals(AlarmLogOutcome.FX_UNSURE, entry.outcome)
        assertTrue("fällig, aber zurückgehalten", entry.notable)

        f.rates.skip = FiatAlarmGuard.Skip.FX_STALE
        f.process(101.5)
        assertEquals(AlarmLogOutcome.FX_STALE, f.alarmLog.last { it.alarmId == 7L }.outcome)
        assertTrue(f.notifier.alarmPosts.isEmpty())
    }

    @Test
    fun alarmTextNotesUsdtAssumedOnlyForFiatAlarms() {
        val f = RefreshFixture(listOf(watch()), listOf(chf, above(100.0, id = 3)))
        f.rates.usdtAssumed = true

        assertEquals(2, f.check(101.0, 99.0, NOW))
        val byId = f.notifier.alarmPosts.map { it.first }.zip(f.notifier.textNotes).toMap()
        assertEquals(AlarmTextNote(usdtAssumed = true), byId[7L])
        assertNull("Schwelle in der Quote: nichts umgerechnet", byId[3L])
    }

    @Test
    fun volumeSpikeFromForeignCandlesNamesTheExchange() {
        val spike = AlarmEntity(id = 9, watchId = 1, condition = AlarmCondition.VOLUME_SPIKE, threshold = 2.0)
        val f = RefreshFixture(listOf(watch()), listOf(spike))
        f.volume.spike = VolumeSpike(ratio = 5.0, candleOpenTime = NOW - 60 * minute, source = "Binance")

        assertEquals(1, f.check(101.0, 99.0, NOW))
        assertEquals(AlarmTextNote(candleSource = "Binance"), f.notifier.textNotes.single())
    }

    @Test
    fun textNoteJoinDropsEmptyParts() {
        assertEquals("A · B", AlarmTextNote.join("A", listOf(null, "", "B")))
        assertNull(AlarmTextNote.of())
        assertNull(AlarmTextNote.of(candleSource = " "))
    }
}
