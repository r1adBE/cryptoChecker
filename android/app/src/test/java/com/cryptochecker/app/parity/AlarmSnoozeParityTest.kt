package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.AlarmSnooze
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemeinsame Testfälle `alarm_snooze.json` — «1 h später» (iOS: `AlarmSnoozeTests.swift`). */
class AlarmSnoozeParityTest {

    private val data = ParityJson.load("alarm_snooze.json")

    @Test
    fun snoozeLastsOneHour() {
        assertEquals(data["snoozeMillis"].num()!!.toLong(), AlarmSnooze.SNOOZE_MILLIS)
        data["until"].list().map { it.obj() }.forEach { c ->
            assertEquals("until: $c", c["until"].num()!!.toLong(), AlarmSnooze.snoozeUntil(c["now"].num()!!.toLong()))
        }
    }

    @Test
    fun isSnoozed() {
        data["isSnoozed"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                c["name"] as String,
                c["snoozed"],
                AlarmSnooze.isSnoozed(c["until"].num()?.toLong(), c["now"].num()!!.toLong()),
            )
        }
    }

    @Test
    fun pruneKeepsRunningPauses() {
        val c = data["prune"].obj()
        val all = c["all"].obj().entries.associate { it.key.toLong() to it.value.num()!!.toLong() }
        val kept = AlarmSnooze.prune(all, c["now"].num()!!.toLong())
        assertEquals(c["kept"].list().map { it.num()!!.toLong() }.toSet(), kept.keys)
    }
}
