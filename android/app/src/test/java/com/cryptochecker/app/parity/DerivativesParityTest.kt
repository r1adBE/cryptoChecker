package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemeinsame Fälle der Futures-Alarme (`testdata/parity/alarms_derivatives.json`), Gegenstück:
 * `Tests/DerivativesAlarmTests.swift`.
 */
class DerivativesParityTest {

    private val data = ParityJson.load("alarms_derivatives.json")
    private val now = data["now"].num()!!.toLong()

    private fun decision(d: DerivativesAlarm.Decision): String = when (d) {
        DerivativesAlarm.Decision.None -> "none"
        DerivativesAlarm.Decision.Rearm -> "rearm"
        is DerivativesAlarm.Decision.Fire -> "fire"
    }

    /** [minutesAgo, units] → Messung relativ zu `now`. */
    private fun points(raw: Any?): List<DerivativesAlarm.OiPoint> = raw.list().map { item ->
        val pair = item.list()
        DerivativesAlarm.OiPoint(units = pair[1].num()!!, time = now - (pair[0].num()!! * 60_000.0).toLong())
    }

    private fun minutesAgo(points: List<DerivativesAlarm.OiPoint>): List<Double> =
        points.map { (now - it.time) / 60_000.0 }

    @Test
    fun decide() {
        var count = 0
        data["decide"].list().forEach { raw ->
            val c = raw.obj()
            val result = DerivativesAlarm.decide(
                condition = AlarmCondition.valueOf(c["condition"] as String),
                threshold = c["threshold"].num()!!,
                value = c["value"].num(),
                armed = c["armed"] as Boolean,
                enabled = c["enabled"] as? Boolean ?: true,
                lastTriggeredAt = c["lastTriggeredAt"].num()?.toLong() ?: 0L,
                now = now,
                cooldownMinutes = c["cooldownMinutes"].num()!!.toInt(),
            )
            assertEquals("decide: ${c["name"]}", c["expected"], decision(result))
            count++
        }
        assertTrue(count >= 20)
    }

    @Test
    fun sequences() {
        val step = data["stepMillis"].num()!!.toLong()
        data["sequences"].list().forEach { raw ->
            val c = raw.obj()
            val condition = AlarmCondition.valueOf(c["condition"] as String)
            val threshold = c["threshold"].num()!!
            val repeating = c["repeating"] as Boolean
            val cooldown = c["cooldownMinutes"].num()!!.toInt()
            var enabled = true
            var referenceAt = 0L
            var lastTriggeredAt = 0L
            var fired = 0
            c["values"].list().forEachIndexed { i, v ->
                val t = now + i * step
                if (!enabled) return@forEachIndexed
                when (DerivativesAlarm.decide(condition, threshold, v.num(), referenceAt <= 0L, enabled, lastTriggeredAt, t, cooldown)) {
                    DerivativesAlarm.Decision.None -> Unit
                    DerivativesAlarm.Decision.Rearm -> referenceAt = 0L
                    is DerivativesAlarm.Decision.Fire -> {
                        fired++
                        lastTriggeredAt = t
                        referenceAt = t
                        enabled = repeating
                    }
                }
            }
            assertEquals("sequence: ${c["name"]}", c["expectedFires"].num()!!.toInt(), fired)
        }
    }

    @Test
    fun openInterest() {
        data["oiChange"].list().forEach { raw ->
            val c = raw.obj()
            val result = DerivativesAlarm.oiChangePercent(points(c["history"]), c["current"].num(), c["hours"].num()!!.toInt(), now)
            val expected = c["expected"].num()
            if (expected == null) assertNull("oiChange: ${c["name"]}", result)
            else {
                assertNotNull("oiChange: ${c["name"]}", result)
                assertEquals("oiChange: ${c["name"]}", expected, result!!, 1e-9)
            }
        }
        data["prune"].list().forEach { raw ->
            val c = raw.obj()
            val result = minutesAgo(DerivativesAlarm.pruneOi(points(c["history"]), now))
            assertEquals("prune: ${c["name"]}", c["expected"].list().map { it.num()!! }, result)
        }
        data["append"].list().forEach { raw ->
            val c = raw.obj()
            val point = points(listOf(c["point"])).single()
            val result = minutesAgo(DerivativesAlarm.appendOi(points(c["history"]), point, now))
            assertEquals("append: ${c["name"]}", c["expected"].list().map { it.num()!! }, result)
        }
    }

    @Test
    fun inputAndAvailability() {
        data["parseFunding"].list().forEach { raw ->
            val c = raw.obj()
            val result = DerivativesAlarm.parseFunding(c["text"] as String, (c["decimalSeparator"] as String).single())
            val expected = c["expected"].num()
            if (expected == null) assertNull("parseFunding: ${c["text"]}", result)
            else assertEquals("parseFunding: ${c["text"]}", expected, result!!, 1e-12)
        }
        data["supports"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(
                "supports: ${c["marketKey"]}",
                c["expected"],
                DerivativesAlarm.supports(c["marketKey"] as String, c["perpetual"] as Boolean),
            )
        }
        data["oiWindow"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(c["expected"].num()!!.toInt(), DerivativesAlarm.oiWindowHours(c["hours"].num()!!.toInt()))
        }
    }
}
