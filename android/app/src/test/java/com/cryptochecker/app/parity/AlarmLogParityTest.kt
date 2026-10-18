package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmLog
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.domain.alarm.AlarmLogState
import com.cryptochecker.app.domain.alarm.AlarmLogTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alarm-Protokoll (`testdata/parity/alarm_log.json`): Ergebnis je beobachteter Prüfung, Melde-Ablauf, Zusammenfassen
 * im Ringpuffer und Dateiformat. Gegenstück: `Tests/AlarmLogParityTests.swift`.
 */
class AlarmLogParityTest {

    private val data = ParityJson.load("alarm_log.json")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.objects(): List<Map<String, Any?>> = (this as List<Any?>).map { it.obj() }

    private fun Map<String, Any?>.flag(key: String) = this[key] as Boolean? ?: false

    @Test
    fun codesMatch() {
        val codes = data["codes"].obj()
        assertEquals(AlarmLogOutcome.entries.size, codes.size)
        for ((name, code) in codes) assertEquals(name, code, AlarmLogOutcome.valueOf(name).code)
    }

    @Test
    fun outcomeFromTrace() {
        for (case in data["outcome"].objects()) {
            val t = case["trace"].obj()
            val trace = AlarmLogTrace(
                noPrice = t.flag("noPrice"),
                lowLiquidity = t.flag("lowLiquidity"),
                markPrice = t.flag("markPrice"),
                stale = t.flag("stale"),
                noRate = t.flag("noRate"),
                due = t.flag("due"),
                coolingDown = t.flag("coolingDown"),
                waitingRearm = t.flag("waitingRearm"),
                quietHours = t.flag("quietHours"),
                notificationsOff = t.flag("notificationsOff"),
                retryBlocked = t.flag("retryBlocked"),
                notShown = t.flag("notShown"),
            )
            val name = case["name"] as String
            val outcome = AlarmLog.outcome(trace)
            assertEquals(name, case["outcome"], outcome.name)
            assertEquals(name, case["notable"], AlarmLog.notable(outcome, trace))
        }
    }

    @Test
    fun deliveryTrace() {
        for (case in data["delivery"].objects()) {
            val trace = AlarmLog.deliveryTrace(
                holdAlarms = case.flag("hold"),
                canShow = case["canShow"] as Boolean?,
                shown = case["shown"] as Boolean?,
                saved = case.flag("saved"),
            )
            val expected = case["outcome"] as String?
            if (expected == null) {
                assertNull(case.toString(), trace)
            } else {
                assertEquals(case.toString(), expected, AlarmLog.outcome(trace!!).name)
            }
        }
    }

    private fun entry(m: Map<String, Any?>) = AlarmLogEntry(
        alarmId = m["id"].num()!!.toLong(),
        at = m["at"].num()!!.toLong(),
        pair = "BTC/USDT",
        exchange = "Binance",
        quote = "USDT",
        price = 100.0,
        condition = AlarmCondition.PRICE_ABOVE,
        threshold = 100.0,
        outcome = AlarmLogOutcome.fromCode(m["out"] as String)!!,
        notable = m.flag("n"),
    )

    @Test
    fun recordCollapsesRepeatsButNeverFirings() {
        val case = data["record"].obj()
        val state = AlarmLog.record(AlarmLogState.EMPTY, case["entries"].objects().map(::entry), case["now"].num()!!.toLong())
        val events = case["events"].objects()
        assertEquals(events.size, state.events.size)
        events.zip(state.events).forEach { (expected, actual) ->
            assertEquals(expected["id"].num()!!.toLong(), actual.alarmId)
            assertEquals(expected["at"].num()!!.toLong(), actual.at)
            assertEquals(expected["until"].num()!!.toLong(), actual.until)
            assertEquals(expected["out"], actual.outcome.code)
        }
        val last = case["lastChecks"].objects()
        assertEquals(last.size, state.lastChecks.size)
        for (expected in last) {
            val actual = state.lastChecks.getValue(expected["id"].num()!!.toLong())
            assertEquals(expected["at"].num()!!.toLong(), actual.at)
            assertEquals(expected["out"], actual.outcome.code)
        }
    }

    @Test
    fun decodeFileFormat() {
        val case = data["decode"].obj()
        val state = AlarmLog.decode(case["text"] as String)
        val last = case["lastChecks"].objects()
        assertEquals(last.size, state.lastChecks.size)
        for (expected in last) {
            val actual = state.lastChecks.getValue(expected["id"].num()!!.toLong())
            assertEquals(expected["at"].num()!!.toLong(), actual.at)
            assertEquals(expected["pair"], actual.pair)
            assertEquals(expected["exchange"], actual.exchange)
            assertEquals(expected["price"].num()!!, actual.price!!, 1e-9)
            assertEquals(expected["condition"], actual.condition.name)
            assertEquals(expected["threshold"].num()!!, actual.threshold, 1e-9)
            assertEquals(expected["outcome"], actual.outcome.name)
        }
        val events = case["events"].objects()
        assertEquals("unbekanntes Ergebnis fällt weg", events.size, state.events.size)
        events.zip(state.events).forEach { (expected, actual) ->
            assertEquals(expected["id"].num()!!.toLong(), actual.alarmId)
            assertEquals(expected["at"].num()!!.toLong(), actual.at)
            assertEquals(expected["price"].num(), actual.price)
            assertEquals(expected["outcome"], actual.outcome.name)
            assertEquals(expected["exchangeTime"].num()?.toLong(), actual.exchangeTime)
            assertEquals(expected["until"].num()!!.toLong(), actual.until)
            assertEquals(expected["notable"], actual.notable)
            assertEquals(expected["currency"], actual.currency)
            assertEquals(expected["shownPrice"].num(), actual.shownPrice)
        }
        assertTrue(AlarmLog.decode(case["wrongVersion"] as String).events.isEmpty())
        // Hin und zurück unverändert
        assertEquals(state, AlarmLog.decode(AlarmLog.encode(state)))
    }

    @Test
    fun limits() {
        val limits = data["limits"].obj()
        assertEquals(limits["maxEvents"].num()!!.toInt(), AlarmLog.MAX_EVENTS)
        assertEquals(limits["retentionDays"].num()!!.toLong() * 24 * 60 * 60_000, AlarmLog.RETENTION_MILLIS)
        assertEquals(limits["liveWriteIntervalMillis"].num()!!.toLong(), AlarmLog.LIVE_WRITE_INTERVAL_MILLIS)
    }
}
