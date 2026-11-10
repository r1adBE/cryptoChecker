package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.refresh.CheckTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Pünktlich prüfen» im gewählten Intervall und Sperrfenster gegen doppelte Läufe (`testdata/parity/exact_cadence.json`):
 * [BackgroundSchedule.exactIntervalMillis], [BackgroundSchedule.needsArm], [BackgroundSchedule.dedupeWindowMillis] und
 * [BackgroundSchedule.shouldSkip]. Gegenstück: `Tests/ExactCadenceParityTests.swift`.
 */
class ExactCadenceParityTest {

    private val data = ParityJson.load("exact_cadence.json")
    private val now = data["now"].num()!!.toLong()

    private fun Any?.int(): Int = num()!!.toInt()

    @Test
    fun intervallWieGemeinsameTestfaelle() {
        val cases = data["interval"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val minutes = c["minutes"].int()
            assertEquals("$minutes", c["expected"].num()!!.toLong(), BackgroundSchedule.exactIntervalMillis(minutes))
            val interval = BackgroundSchedule.exactIntervalMillis(minutes)
            assertEquals(now + interval, BackgroundSchedule.nextExactAt(now, interval))
        }
    }

    @Test
    fun neuStellenWieGemeinsameTestfaelle() {
        for (raw in data["needsArm"].list()) {
            val c = raw.obj()
            val interval = BackgroundSchedule.exactIntervalMillis(c["minutes"].int())
            val armedFor = now + c["armedIn"].num()!!.toLong()
            assertEquals(c["name"] as String, c["expected"] as Boolean,
                BackgroundSchedule.needsArm(armedFor, now, intervalMillis = interval))
        }
    }

    @Test
    fun sperrfensterWieGemeinsameTestfaelle() {
        val cases = data["skip"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val trigger = CheckTrigger.fromCode(c["trigger"] as String)!!
            val window = BackgroundSchedule.dedupeWindowMillis(
                trigger, exactArmed = c["exactArmed"] as Boolean,
                intervalMillis = BackgroundSchedule.exactIntervalMillis(c["minutes"].int()),
            )
            val last = c["age"].num()?.let { now - it.toLong() } ?: 0L
            assertEquals(c["name"] as String, c["expected"] as Boolean, BackgroundSchedule.shouldSkip(trigger, last, now, window))
        }
    }
}
