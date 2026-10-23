package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.alarm.AlarmReliability
import com.cryptochecker.app.domain.alarm.OemBattery
import com.cryptochecker.app.domain.refresh.CheckRunLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zeile über der Merkliste (`testdata/parity/alarm_reliability.json`): gelesener Zustand → ein Satz und Farbe.
 * Gegenstück: `Tests/AlarmReliabilityTests.swift`.
 */
class AlarmReliabilityParityTest {

    private val data = ParityJson.load("alarm_reliability.json")

    private fun bool(m: Map<String, Any?>, key: String, default: Boolean): Boolean = m[key] as? Boolean ?: default

    /** Gespeicherte Läufe («at;trigger;result;dauer»), falls der Fall sie nennt. */
    private fun runs(m: Map<String, Any?>) =
        (m["runs"] as? List<*>)?.let { lines -> CheckRunLog.decode(lines.joinToString("\n")) }

    private fun input(m: Map<String, Any?>) = AlarmReadiness.Input(
        platform = if (m["platform"] == "ios") AlarmReadiness.Platform.IOS else AlarmReadiness.Platform.ANDROID,
        notifications = AlarmReadiness.Access.entries.first { it.code == m["notifications"] },
        now = m["now"].num()!!.toLong(),
        minuteOfDay = m["minuteOfDay"].num()!!.toInt(),
        lastBackgroundCheck = runs(m)?.let { CheckRunLog.lastBackgroundCheck(it) } ?: m["lastBackgroundCheck"].num()?.toLong(),
        lastFailedBackgroundCheck = runs(m)?.let { CheckRunLog.lastFailedBackgroundCheck(it) },
        storageNoKey = bool(m, "storageNoKey", false),
        hasAlarms = bool(m, "hasAlarms", false),
        channel = (m["channel"] as String?)?.let { code -> AlarmReadiness.Channel.entries.first { it.code == code } },
        batteryUnrestricted = bool(m, "batteryUnrestricted", false),
        backgroundRestricted = bool(m, "backgroundRestricted", false),
        oemVendor = if (bool(m, "oem", false)) OemBattery.Vendor.XIAOMI else null,
        backgroundRefresh = bool(m, "backgroundRefresh", true),
    )

    @Test
    fun staleLimitMatches() {
        assertEquals(data["staleMillis"].num()!!.toLong(), AlarmReliability.STALE_MILLIS)
        assertEquals(data["staleMillisIos"].num()!!.toLong(), AlarmReliability.STALE_MILLIS_IOS)
        assertEquals(AlarmReliability.STALE_MILLIS, AlarmReliability.staleMillis(AlarmReadiness.Platform.ANDROID))
        assertEquals(AlarmReadiness.STALE_CHECK_MILLIS, AlarmReliability.staleMillis(AlarmReadiness.Platform.IOS))
    }

    @Test
    fun status() {
        val cases = data["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 20)
        // Jeder Satz kommt im gemeinsamen Testfall vor
        assertEquals(AlarmReliability.Kind.entries.map { it.code }.toSet(), cases.map { it["kind"] as String }.toSet())
        for (c in cases) {
            val actual = AlarmReliability.status(input(c["input"].obj()))
            assertEquals(c["name"] as String, c["kind"], actual.kind.code)
            assertEquals(c["name"] as String, c["warn"], actual.warn)
        }
    }
}
