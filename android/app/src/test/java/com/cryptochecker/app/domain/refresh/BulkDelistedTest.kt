package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemeinsame Testfälle `bulk_delisted.json` (iOS: `BulkDelistedTests` in `RefreshTests.swift`). */
class BulkDelistedTest {

    private val data = ParityJson.load("bulk_delisted.json")

    private fun strings(v: Any?): List<String> = v.list().map { it as String }

    private fun state(v: Any?): BulkDelisted.State? = v?.obj()?.let {
        BulkDelisted.State(strings(it["delisted"]).toSet(), it["recheckedAt"].num()!!.toLong())
    }

    @Test
    fun recheckInterval() {
        assertEquals(data["recheckMillis"].num()!!.toLong(), BulkDelisted.RECHECK_MILLIS)
    }

    @Test
    fun plan() {
        data["plan"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val plan = BulkDelisted.plan(
                state(c["state"]), strings(c["pairIds"]), strings(c["notTraded"]).toSet(), c["now"].num()!!.toLong(),
            )
            assertEquals(name, strings(c["request"]), plan.request)
            assertEquals(name, c["recheck"] as Boolean, plan.recheck)
            assertEquals(name, strings(c["pairIds"]), plan.all)
        }
    }

    @Test
    fun learn() {
        data["learn"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val p = c["plan"].obj()
            val plan = BulkDelisted.Plan(strings(p["all"]), strings(p["request"]), p["recheck"] as Boolean)
            val next = BulkDelisted.learn(
                state(c["state"]), plan, strings(c["tickers"]).toSet(), strings(c["failed"]).toSet(),
                c["complete"] as Boolean, c["now"].num()!!.toLong(),
                // Teilabfrage gescheitert (z. B. Alpha): keine Aussage über diese Paare
                (c["incomplete"] as? List<*>).orEmpty().map { it as String }.toSet(),
            )
            assertEquals(name, strings(c["delisted"]).toSet(), next.delisted)
            assertEquals(name, c["recheckedAt"].num()!!.toLong(), next.recheckedAt)
        }
    }
}
