package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioAlarmPause
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

    /** Unvollständiger Stand (seit Build 94): Adressen, Wesentlichkeit alter Kurse, Hinweis «pausiert». */
    @Test
    fun incomplete() {
        val block = data["incomplete"].obj()
        assertEquals(block["immaterialShare"].num()!!, PortfolioAlarmLogic.IMMATERIAL_SHARE, 0.0)
        block["cases"].list().map { it.obj() }.forEach { c ->
            val r = c["reading"].obj()
            val reading = PortfolioReading(
                total = 0.0,
                currency = "USDT",
                totalUsdt = r["totalUsdt"].num(),
                changePercent = 1.0,
                empty = false,
                missingPrices = r["missingPrices"].num()?.toInt() ?: 0,
                todaySkipped = r["todaySkipped"].num()?.toInt() ?: 0,
                stalePrices = r["stalePrices"].num()?.toInt() ?: 0,
                staleValueUsdt = r["staleValueUsdt"].num(),
                addressesPending = r["addressesPending"].num()?.toInt() ?: 0,
            )
            val name = c["name"] as String
            assertEquals("Wert: $name", c["value"], PortfolioAlarmLogic.incomplete(PortfolioAlarmKind.VALUE_BELOW, reading))
            assertEquals("Wert darüber: $name", c["value"], PortfolioAlarmLogic.incomplete(PortfolioAlarmKind.VALUE_ABOVE, reading))
            assertEquals("Veränderung: $name", c["change"], PortfolioAlarmLogic.incomplete(PortfolioAlarmKind.CHANGE_UP, reading))
            val pause = PortfolioAlarmLogic.pause(
                c["kinds"].list().map { PortfolioAlarmKind.valueOf(it as String) }, reading,
                (c["missingCoins"] as List<*>?)?.map { it as String }.orEmpty(),
                (c["staleCoins"] as List<*>?)?.map { it as String }.orEmpty(),
            )
            val expected: PortfolioAlarmPause? = when (val p = c["pause"]) {
                null -> null
                "addresses" -> PortfolioAlarmPause.AddressesPending(r["addressesPending"].num()!!.toInt())
                else -> PortfolioAlarmPause.NoPrice(p.list().map { it as String })
            }
            assertEquals("pause: $name", expected, pause)
        }
    }
}
