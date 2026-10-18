package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ringpuffer und letzte Prüfung des Alarm-Protokolls ([AlarmLog]). Wie `AlarmLogTests` (iOS). */
class AlarmLogTest {

    private val day = 24 * 60 * 60_000L
    private val now = 1_760_000_000_000L

    private fun entry(
        id: Long,
        at: Long,
        outcome: AlarmLogOutcome = AlarmLogOutcome.FIRED,
        notable: Boolean = outcome == AlarmLogOutcome.FIRED,
        price: Double = 100.0,
    ) = AlarmLogEntry(
        alarmId = id, at = at, pair = "BTC/CHF", exchange = "Kraken", quote = "CHF", price = price,
        condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0, outcome = outcome, notable = notable,
    )

    @Test
    fun ringBufferKeepsTheNewest200() {
        var state = AlarmLogState.EMPTY
        for (i in 1..250) state = AlarmLog.record(state, listOf(entry(i.toLong(), now - 250 + i)), now)
        assertEquals(AlarmLog.MAX_EVENTS, state.events.size)
        // Neueste zuerst, die ältesten 50 sind weg
        assertEquals(250L, state.events.first().alarmId)
        assertEquals(51L, state.events.last().alarmId)
    }

    @Test
    fun entriesOlderThan14DaysArePruned() {
        val old = entry(1, now - 15 * day)
        val recent = entry(2, now - 13 * day)
        val state = AlarmLog.record(AlarmLogState(events = listOf(recent, old)), emptyList(), now)
        assertEquals(listOf(2L), state.events.map { it.alarmId })
        // Grenze: genau 14 Tage bleiben
        assertEquals(1, AlarmLog.prune(listOf(entry(3, now - 14 * day)), now).size)
    }

    @Test
    fun lastCheckIsOverwrittenEachCheck() {
        var state = AlarmLog.record(AlarmLogState.EMPTY, listOf(entry(7, now - 2_000, AlarmLogOutcome.NOT_REACHED, price = 99_000.0)), now)
        state = AlarmLog.record(state, listOf(entry(7, now - 1_000, AlarmLogOutcome.STALE, notable = false, price = 99_500.0)), now)
        assertEquals(1, state.lastChecks.size)
        assertEquals(AlarmLogOutcome.STALE, state.lastChecks.getValue(7).outcome)
        assertEquals(99_500.0, state.lastChecks.getValue(7).price!!, 0.0)
        // Nicht bemerkenswert: nichts im Protokoll
        assertTrue(state.events.isEmpty())
        // Eine ältere Prüfung (anderer Prozess, später geschrieben) überschreibt die neuere nicht
        state = AlarmLog.record(state, listOf(entry(7, now - 5_000, AlarmLogOutcome.NOT_REACHED, notable = false)), now)
        assertEquals(AlarmLogOutcome.STALE, state.lastChecks.getValue(7).outcome)
    }

    @Test
    fun onlyNotableChecksGoToTheLog() {
        val state = AlarmLog.record(
            AlarmLogState.EMPTY,
            listOf(
                entry(1, now, AlarmLogOutcome.NOT_REACHED, notable = false),
                entry(2, now, AlarmLogOutcome.FIRED),
                entry(3, now, AlarmLogOutcome.QUIET_HOURS, notable = true),
            ),
            now,
        )
        assertEquals(setOf(2L, 3L), state.events.map { it.alarmId }.toSet())
        assertEquals(3, state.lastChecks.size)
    }

    @Test
    fun repeatedHoldIsOneEntryWithRange() {
        var state = AlarmLogState.EMPTY
        for (i in 0 until 30) {
            state = AlarmLog.record(state, listOf(entry(1, now + i * 900_000L, AlarmLogOutcome.QUIET_HOURS, notable = true)), now + i * 900_000L)
        }
        assertEquals(1, state.events.size)
        assertEquals(now, state.events.single().at)
        assertEquals(now + 29 * 900_000L, state.events.single().until)
        // Ein anderer Grund danach beginnt einen neuen Eintrag
        state = AlarmLog.record(state, listOf(entry(1, now + 31 * 900_000L, AlarmLogOutcome.STALE, notable = true)), now + 31 * 900_000L)
        assertEquals(2, state.events.size)
        assertEquals(AlarmLogOutcome.STALE, state.events.first().outcome)
    }

    @Test
    fun onlyFiredOrANewOutcomeStartsAnEvent() {
        val held = AlarmLog.record(AlarmLogState.EMPTY, listOf(entry(1, now, AlarmLogOutcome.QUIET_HOURS, true)), now)
        // Gleiches Ergebnis gleich danach: verlängert nur «von … bis …»
        assertFalse(AlarmLog.startsEvent(held, listOf(entry(1, now + 1_000, AlarmLogOutcome.QUIET_HOURS, true))))
        // Nicht bemerkenswert: nie
        assertFalse(AlarmLog.startsEvent(held, listOf(entry(1, now + 1_000, AlarmLogOutcome.NOT_REACHED))))
        // Auslösung, anderes Ergebnis, anderer Alarm: neuer Eintrag
        assertTrue(AlarmLog.startsEvent(held, listOf(entry(1, now + 1_000))))
        assertTrue(AlarmLog.startsEvent(held, listOf(entry(1, now + 1_000, AlarmLogOutcome.COOLDOWN, true))))
        assertTrue(AlarmLog.startsEvent(held, listOf(entry(2, now + 1_000, AlarmLogOutcome.QUIET_HOURS, true))))
        assertTrue(AlarmLog.startsEvent(AlarmLogState.EMPTY, listOf(entry(1, now, AlarmLogOutcome.QUIET_HOURS, true))))
        // Mehrere im selben Durchlauf: der zweite verlängert den ersten
        assertFalse(
            AlarmLog.startsEvent(
                held,
                listOf(
                    entry(1, now + 2_000, AlarmLogOutcome.QUIET_HOURS, true),
                    entry(1, now + 1_000, AlarmLogOutcome.QUIET_HOURS, true),
                ),
            ),
        )
    }

    @Test
    fun deletingAnAlarmRemovesItsLastCheckButKeepsLogEntries() {
        val state = AlarmLog.record(AlarmLogState.EMPTY, listOf(entry(7, now), entry(8, now)), now)
        val after = AlarmLog.removeAlarm(state, 7)
        assertFalse(7L in after.lastChecks)
        assertTrue(8L in after.lastChecks)
        // Protokoll-Einträge bleiben mit ihrem Text
        assertEquals(2, after.events.size)
        assertEquals("BTC/CHF", after.events.first { it.alarmId == 7L }.pair)
    }

    @Test
    fun retainAndClear() {
        val state = AlarmLog.record(AlarmLogState.EMPTY, listOf(entry(1, now), entry(2, now)), now)
        assertEquals(setOf(2L), AlarmLog.retainAlarms(state, setOf(2L, 99L)).lastChecks.keys)
        val cleared = AlarmLog.clearEvents(state)
        assertTrue(cleared.events.isEmpty())
        assertEquals(2, cleared.lastChecks.size)
    }

    @Test
    fun coversPriceAndPercentAlarmsOnly() {
        val covered = AlarmCondition.entries.filter(AlarmLog::covers).toSet()
        assertEquals(
            setOf(
                AlarmCondition.PRICE_ABOVE, AlarmCondition.PRICE_BELOW,
                AlarmCondition.CHANGE_PERCENT_UP, AlarmCondition.CHANGE_PERCENT_DOWN, AlarmCondition.MOVE_PERCENT_WINDOW,
            ),
            covered,
        )
    }

    @Test
    fun brokenFileIsEmpty() {
        assertTrue(AlarmLog.decode("kaputt").events.isEmpty())
        assertTrue(AlarmLog.decode(null).lastChecks.isEmpty())
    }
}
