package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gemeinsame Testfälle `portfolio_alarm.json` (iOS: `PortfolioAlarmParityTests.swift`). */
class PortfolioAlarmParityTest {

    private val data = ParityJson.load("portfolio_alarm.json")

    /** Alarm in einer anderen Währung als der Stand: USDT zum Marktkurs wie der Portfolio-Kopf. */
    @Test
    fun totalIn() {
        data["totalIn"].list().map { it.obj() }.forEach { c ->
            val r = c["reading"].obj()
            val reading = PortfolioReading(
                total = r["total"].num()!!,
                currency = r["currency"] as String,
                totalUsdt = r["totalUsdt"].num(),
                changePercent = null,
                empty = false,
                usdTo = r["usdTo"].obj().mapValues { it.value.num()!! },
                usdtUsdLive = r["usdtUsdLive"].num(),
            )
            val actual = PortfolioAlarmLogic.totalIn(c["currency"] as String, reading)
            val expected = c["expected"].num()
            val name = c["name"] as String
            if (expected == null) assertNull(name, actual) else assertEquals(name, expected, actual!!, 1e-6)
        }
    }
}
