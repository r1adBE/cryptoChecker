package com.cryptochecker.app.parity

import com.cryptochecker.app.work.BackgroundWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bedarf der periodischen Hintergrund-Aktualisierung (`testdata/parity/periodic_needed.json`, seit Build 103):
 * [BackgroundWorkPolicy.periodicNeeded] und [BackgroundWorkPolicy.portfolioWork]. Gegenstück:
 * `Tests/PeriodicNeededParityTests.swift` (iOS plant bzw. sagt die BGTask-Anfrage danach ab).
 */
class PeriodicNeededParityTest {

    private val data = ParityJson.load("periodic_needed.json")

    private fun Map<String, Any?>.flag(key: String): Boolean = this[key] as? Boolean ?: false

    @Test
    fun periodicNeededWieGemeinsameTestfaelle() {
        val cases = data["periodicNeeded"].list()
        assertTrue(cases.size >= 15)
        for (raw in cases) {
            val c = raw.obj()
            val d = c["demand"].obj()
            val demand = BackgroundWorkPolicy.WorkDemand(
                pairs = (d["pairs"] as? List<*> ?: emptyList<Any?>()).map {
                    val p = it.obj()
                    BackgroundWorkPolicy.PairWork(
                        disabled = p.flag("disabled"),
                        hasEnabledAlarm = p.flag("hasEnabledAlarm"),
                        notificationEnabled = p.flag("notificationEnabled"),
                        ttsEnabled = p.flag("ttsEnabled"),
                    )
                },
                priceNotifications = d.flag("priceNotifications"),
                ttsPrices = d.flag("ttsPrices"),
                activityAlerts = d.flag("activityAlerts"),
                pairWidgets = d.flag("pairWidgets"),
                portfolioWork = d.flag("portfolioWork"),
                marketAlarms = d.flag("marketAlarms"),
                watchlistKept = d.flag("watchlistKept"),
                pulseWidget = d.flag("pulseWidget"),
            )
            assertEquals(c["name"] as String, c["expected"] as Boolean, BackgroundWorkPolicy.periodicNeeded(demand))
        }
    }

    @Test
    fun portfolioWorkWieGemeinsameTestfaelle() {
        val cases = data["portfolioWork"].list()
        assertTrue(cases.isNotEmpty())
        for (raw in cases) {
            val c = raw.obj()
            assertEquals(
                c["name"] as String,
                c["expected"] as Boolean,
                BackgroundWorkPolicy.portfolioWork(
                    portfolioEnabled = c.flag("portfolioEnabled"),
                    alarmOn = c.flag("alarmOn"),
                    portfolioKept = c.flag("portfolioKept"),
                    widget = c.flag("widget"),
                ),
            )
        }
    }
}
