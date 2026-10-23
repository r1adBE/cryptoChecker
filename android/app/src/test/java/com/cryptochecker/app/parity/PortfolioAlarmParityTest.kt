package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioAlarmPause
import com.cryptochecker.app.domain.alarm.PortfolioReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gemeinsame Testfälle `portfolio_alarm.json` (iOS: `PortfolioAlarmParityTests.swift`). */
class PortfolioAlarmParityTest {

    private val data = ParityJson.load("portfolio_alarm.json")

    /** FUN3-01: «fällt um» nur unter 100 %; aus einer Sicherung ≥ 100 % übernommen, aber aus. */
    @Test
    fun threshold() {
        val block = data["threshold"].obj()
        assertEquals(block["maxFallPercent"].num()!!, PortfolioAlarmLogic.MAX_FALL_PERCENT, 0.0)
        val cases = block["cases"].list().map { it.obj() }
        assertEquals(8, cases.size)
        cases.forEach { c ->
            val name = c["name"] as String
            val kind = PortfolioAlarmKind.valueOf(c["kind"] as String)
            val threshold = c["threshold"].num()!!
            assertEquals("$name: gültig", c["valid"] as Boolean, PortfolioAlarmLogic.isValidThreshold(kind, threshold))
            assertEquals("$name: fällt ≥ 100", c["exceedsFall"] as Boolean, PortfolioAlarmLogic.exceedsFall(kind, threshold))
            val json = JSONObject().put("id", 1).put("kind", kind.name).put("threshold", threshold)
                .put("currency", if (kind.isValue) "CHF" else JSONObject.NULL).put("enabled", true).put("repeating", false)
            val restored = PortfolioJson.jsonToAlarm(json)
            assertEquals("$name: übernommen", c["restored"] as Boolean, restored != null)
            assertEquals("$name: danach an", c["restoredEnabled"] as Boolean, restored?.enabled ?: false)
        }
    }

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

    /** Unvollständiger Stand: Adressen, Wesentlichkeit alter Kurse und Token, Hinweis «pausiert». */
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
                addressesFailing = (r["addressesFailing"] as List<*>?)?.map { it as String }.orEmpty(),
                staleTokenAddresses = (r["staleTokenAddresses"] as List<*>?)?.map { it as String }.orEmpty(),
                staleTokenValueUsdt = r["staleTokenValueUsdt"].num() ?: 0.0,
                unverifiedCoins = (r["unverifiedCoins"] as List<*>?)?.map { it as String }.orEmpty(),
                unverifiedValueUsdt = r["unverifiedValueUsdt"].num() ?: 0.0,
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
                // {"tokens":[…]} bzw. {"failing":[…]} bzw. {"unverified":[…]}
                is Map<*, *> -> p.obj()["tokens"]?.let { t -> PortfolioAlarmPause.TokensStale(t.list().map { it as String }) }
                    ?: p.obj()["unverified"]?.let { u -> PortfolioAlarmPause.PriceUnverified(u.list().map { it as String }) }
                    ?: PortfolioAlarmPause.AddressesFailing(p.obj()["failing"].list().map { it as String })
                else -> PortfolioAlarmPause.NoPrice(p.list().map { it as String })
            }
            assertEquals("pause: $name", expected, pause)
        }
    }
}
