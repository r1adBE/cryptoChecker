package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.model.AlarmCondition
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

    @Test
    fun offeredOnlyWherePauseWorks() {
        val cases = data["offeredFor"].list().map { it.obj() }
        // Jede Alarm-Art muss im gemeinsamen Testfall vorkommen, damit eine neue Art nicht unbemerkt bleibt
        assertEquals(AlarmCondition.entries.map { it.name }.toSet(), cases.map { it["condition"] as String }.toSet())
        cases.forEach { c ->
            assertEquals(
                "${c["name"]} (${c["condition"]}, repeating=${c["repeating"]})",
                c["offered"],
                AlarmSnooze.offeredFor(AlarmCondition.valueOf(c["condition"] as String), c["repeating"] as Boolean),
            )
        }
    }

    @Test
    fun rearmsOnlyPriceLevels() {
        val cases = data["rearmsOnSnooze"].list().map { it.obj() }
        assertEquals(AlarmCondition.entries.map { it.name }.toSet(), cases.map { it["condition"] as String }.toSet())
        cases.forEach { c ->
            val condition = AlarmCondition.valueOf(c["condition"] as String)
            assertEquals(c["condition"] as String, c["rearms"], AlarmSnooze.rearmsOnSnooze(condition))
            // Wer wieder scharf gestellt wird, bekommt den Knopf auch bei «einmal»
            if (c["rearms"] == true) assertEquals(true, AlarmSnooze.offeredFor(condition, repeating = false))
        }
    }
}
