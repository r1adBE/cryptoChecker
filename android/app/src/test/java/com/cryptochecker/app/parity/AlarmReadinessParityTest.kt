package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.alarm.QuietHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seite «Damit Alarme ankommen» (`testdata/parity/alarm_delivery.json`): gelesener Zustand → Zeilen mit Zustand, Text
 * und Knopf. Gegenstück: `Tests/AlarmReadinessParityTests.swift`.
 */
class AlarmReadinessParityTest {

    private val data = ParityJson.load("alarm_delivery.json")

    private fun bool(m: Map<String, Any?>, key: String, default: Boolean): Boolean = m[key] as? Boolean ?: default

    private fun input(m: Map<String, Any?>): AlarmReadiness.Input = AlarmReadiness.Input(
        platform = if (m["platform"] == "ios") AlarmReadiness.Platform.IOS else AlarmReadiness.Platform.ANDROID,
        notifications = AlarmReadiness.Access.entries.first { it.code == m["notifications"] },
        now = m["now"].num()!!.toLong(),
        minuteOfDay = m["minuteOfDay"].num()!!.toInt(),
        quietEnabled = bool(m, "quietEnabled", false),
        quietStart = m["quietStart"].num()?.toInt() ?: QuietHours.DEFAULT_START,
        quietEnd = m["quietEnd"].num()?.toInt() ?: QuietHours.DEFAULT_END,
        lastBackgroundCheck = m["lastBackgroundCheck"].num()?.toLong(),
        hasAlarms = bool(m, "hasAlarms", false),
        channel = (m["channel"] as String?)?.let { code -> AlarmReadiness.Channel.entries.first { it.code == code } },
        exactEnabled = bool(m, "exactEnabled", false),
        exactPermitted = bool(m, "exactPermitted", true),
        batteryUnrestricted = bool(m, "batteryUnrestricted", false),
        backgroundRestricted = bool(m, "backgroundRestricted", false),
        systemDnd = m["systemDnd"] as Boolean?,
        alertEnabled = bool(m, "alertEnabled", true),
        soundEnabled = bool(m, "soundEnabled", true),
        wantsSound = bool(m, "wantsSound", true),
        timeSensitive = m["timeSensitive"] as Boolean?,
        backgroundRefresh = bool(m, "backgroundRefresh", true),
        lowPower = bool(m, "lowPower", false),
    )

    @Test
    fun staleLimitMatches() {
        assertEquals(AlarmReadiness.STALE_CHECK_MILLIS, data["staleCheckMillis"].num()!!.toLong())
    }

    @Test
    fun rows() {
        val cases = data["cases"].list()
        assertTrue(cases.size >= 10)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val actual = AlarmReadiness.rows(input(c["input"].obj()))
                .map { listOf(it.row.code, it.state.code, it.detail.code, it.action.code) }
            val expected = c["expected"].list().map { row -> row.list().map { it as String } }
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun okRowsHaveNoButton() {
        for (raw in data["cases"].list()) {
            val items = AlarmReadiness.rows(input(raw.obj()["input"].obj()))
            items.filter { it.state == AlarmReadiness.State.OK }
                .forEach { assertEquals(AlarmReadiness.Action.NONE, it.action) }
        }
    }

    @Test
    fun neverAsksForBatteryExemption() {
        // Projektentscheid: nur die Akku-Seite der App öffnen, nie ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        val actions = AlarmReadiness.Action.entries.map { it.code }
        assertFalse(actions.any { it.contains("ignore") || it.contains("exemption") })
        val battery = AlarmReadiness.rows(
            AlarmReadiness.Input(AlarmReadiness.Platform.ANDROID, AlarmReadiness.Access.GRANTED, now = 0, minuteOfDay = 0)
        ).first { it.row == AlarmReadiness.Row.BATTERY }
        assertEquals(AlarmReadiness.State.INFO, battery.state)
        assertEquals(AlarmReadiness.Action.BATTERY_SETTINGS, battery.action)
    }

    @Test
    fun warningSummary() {
        val good = AlarmReadiness.rows(
            AlarmReadiness.Input(
                AlarmReadiness.Platform.ANDROID, AlarmReadiness.Access.GRANTED, now = 10, minuteOfDay = 600,
                lastBackgroundCheck = 5, exactEnabled = true, batteryUnrestricted = true,
            )
        )
        assertFalse(AlarmReadiness.hasWarning(good))
        val bad = AlarmReadiness.rows(
            AlarmReadiness.Input(AlarmReadiness.Platform.IOS, AlarmReadiness.Access.DENIED, now = 10, minuteOfDay = 600)
        )
        assertTrue(AlarmReadiness.hasWarning(bad))
    }

    @Test
    fun backgroundAlarmKinds() {
        val cases = data["backgroundAlarms"].list()
        assertTrue(cases.size >= 10)
        for (raw in cases) {
            val c = raw.obj()
            val k = c["kinds"].obj()
            val kinds = AlarmReadiness.AlarmKinds(
                pairAlarm = bool(k, "pairAlarm", false),
                portfolioEnabled = bool(k, "portfolioEnabled", false),
                portfolioAlarm = bool(k, "portfolioAlarm", false),
                gasEthTenths = k["gasEthTenths"].num()?.toInt() ?: 0,
                gasBtc = k["gasBtc"].num()?.toInt() ?: 0,
                depeg = bool(k, "depeg", false),
                fearGreedBelow = k["fearGreedBelow"].num()?.toInt() ?: 0,
                fearGreedAbove = k["fearGreedAbove"].num()?.toInt() ?: 0,
                indicatorAlert = bool(k, "indicatorAlert", false),
            )
            assertEquals(c["name"] as String, c["expected"] as Boolean, AlarmReadiness.hasBackgroundAlarms(kinds))
        }
    }

    /** Nur Android: Erlaubnis vor «noch nicht gefragt», sonst «aus». */
    @Test
    fun androidAccess() {
        assertEquals(AlarmReadiness.Access.GRANTED, AlarmReadiness.androidAccess(allowed = true, notDetermined = true))
        assertEquals(AlarmReadiness.Access.NOT_DETERMINED, AlarmReadiness.androidAccess(allowed = false, notDetermined = true))
        assertEquals(AlarmReadiness.Access.DENIED, AlarmReadiness.androidAccess(allowed = false, notDetermined = false))
    }
}
