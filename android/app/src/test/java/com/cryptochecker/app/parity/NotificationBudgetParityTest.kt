package com.cryptochecker.app.parity

import com.cryptochecker.app.notification.NotificationBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemeinsame Testfälle `notification_budget.json` — Obergrenze der Kurs-Mitteilungen (iOS:
 * `NotificationBudgetTests.swift`): 50 Paare ergeben höchstens 20 Einzelmeldungen und eine Sammelmeldung.
 */
class NotificationBudgetParityTest {

    private val data = ParityJson.load("notification_budget.json")

    private fun ids(value: Any?): List<Long> = value.list().map { it.num()!!.toLong() }

    @Test
    fun maxPrice() {
        assertEquals(data["maxPrice"].num()!!.toInt(), NotificationBudget.MAX_PRICE)
    }

    @Test
    fun priceSlots() {
        data["priceSlots"].list().map { it.obj() }.forEach { c ->
            assertEquals("$c", c["slots"].num()!!.toInt(), NotificationBudget.priceSlots(c["other"].num()!!.toInt()))
        }
    }

    @Test
    fun planPrices() {
        data["planPrices"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val plan = NotificationBudget.planPrices(
                ids(c["shown"]), ids(c["overflowBefore"]), ids(c["posts"]), c["slots"].num()!!.toInt(),
            )
            assertEquals(name, ids(c["individual"]), plan.individual)
            assertEquals(name, ids(c["overflow"]), plan.overflow)
            assertEquals(name, ids(c["demoted"]), plan.demoted)
            // Nie mehr als 20 einzeln (die Sammelmeldung ist EINE Mitteilung)
            assertTrue(name, plan.individual.size <= NotificationBudget.MAX_PRICE)
        }
    }
}
