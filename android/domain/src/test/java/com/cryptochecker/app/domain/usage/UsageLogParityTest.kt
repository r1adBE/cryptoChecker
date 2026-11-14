package com.cryptochecker.app.domain.usage

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.usage.UsageBucket
import com.cryptochecker.marketdata.usage.UsageLog
import com.cryptochecker.marketdata.usage.UsageReport
import com.cryptochecker.marketdata.usage.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Akku & Aktivität»: Eimer je Stunde, 24-h-Summe, feste Grenzen, Treiber, Datei- und Berichtsformat wie iOS
 * (`testdata/parity/usage_log.json`, [UsageLog]). Gegenstück: `Tests/UsageLogParityTests.swift`.
 */
class UsageLogParityTest {

    private val data = ParityJson.load("usage_log.json")
    private val now = data["now"].num()!!.toLong()

    private fun Any?.long(): Long = num()!!.toLong()
    private fun Any?.int(): Int = num()!!.toInt()

    private fun buckets(raw: Any?): List<UsageBucket> = raw.list().map { row ->
        val r = row.list()
        UsageBucket(r[0].long(), r[1].int(), r[2].int(), r[3].long())
    }

    @Test
    fun grenzenWieGemeinsameTestfaelle() {
        val c = data["constants"].obj()
        assertEquals(c["hourMillis"].long(), UsageLog.HOUR_MILLIS)
        assertEquals(c["windowHours"].int(), UsageLog.WINDOW_HOURS)
        assertEquals(c["keepHours"].int(), UsageLog.KEEP_HOURS)
        assertEquals(c["mediumRuns"].int(), UsageLog.MEDIUM_RUNS)
        assertEquals(c["highRuns"].int(), UsageLog.HIGH_RUNS)
        assertEquals(c["mediumRequests"].int(), UsageLog.MEDIUM_REQUESTS)
        assertEquals(c["highRequests"].int(), UsageLog.HIGH_REQUESTS)
        assertEquals(c["mediumBytes"].long(), UsageLog.MEDIUM_BYTES)
        assertEquals(c["highBytes"].long(), UsageLog.HIGH_BYTES)
    }

    @Test
    fun stundeZusammenfuehrenUndSummeWieGemeinsameTestfaelle() {
        for (raw in data["hourOf"].list()) {
            val c = raw.obj()
            assertEquals("${c["millis"]}", c["hour"].long(), UsageLog.hourOf(c["millis"].long()))
        }
        val merges = data["merge"].list()
        assertTrue(merges.size >= 5)
        for (raw in merges) {
            val c = raw.obj()
            assertEquals(c["name"] as String, buckets(c["expected"]), UsageLog.merge(buckets(c["a"]), buckets(c["b"]), now))
        }
        for (raw in data["add"].list()) {
            val c = raw.obj()
            val added = UsageLog.add(buckets(c["buckets"]), now, c["runs"].int(), c["requests"].int(), c["bytes"].long())
            assertEquals(c["name"] as String, buckets(c["expected"]), added)
        }
        for (raw in data["totals"].list()) {
            val c = raw.obj()
            val e = c["expected"].list()
            assertEquals(c["name"] as String, UsageTotals(e[0].int(), e[1].int(), e[2].long()),
                UsageLog.totals(buckets(c["buckets"]), now))
        }
    }

    @Test
    fun stufeUndTreiberWieGemeinsameTestfaelle() {
        val levels = data["level"].list()
        assertTrue(levels.size >= 10)
        for (raw in levels) {
            val c = raw.obj()
            val totals = UsageTotals(c["runs"].int(), c["requests"].int(), c["bytes"].long())
            assertEquals("$totals", c["expected"], UsageLog.level(totals).code)
        }
        for (raw in data["driver"].list()) {
            val c = raw.obj()
            assertEquals("$c", c["expected"], UsageLog.driver(c["planned"] as Boolean, c["exact"] as Boolean).code)
        }
    }

    @Test
    fun dateiUndBerichtWieGemeinsameTestfaelle() {
        for (raw in data["decode"].list()) {
            val c = raw.obj()
            assertEquals(c["name"] as String, buckets(c["expected"]), UsageLog.decode(c["text"] as String))
        }
        assertTrue(UsageLog.decode(null).isEmpty())
        for (raw in data["encode"].list()) {
            val c = raw.obj()
            val list = buckets(c["buckets"])
            assertEquals(c["text"], UsageLog.encode(list))
            assertEquals(list, UsageLog.decode(UsageLog.encode(list)))
        }
        for (raw in data["utf8"].list()) {
            val c = raw.obj()
            assertEquals(c["text"] as String, c["bytes"].long(), UsageLog.utf8Length(c["text"] as String))
        }
        for (raw in data["reportEncode"].list()) {
            val c = raw.obj()
            val report = UsageReport(c["at"].long(), UsageTotals(c["runs"].int(), c["requests"].int(), c["bytes"].long()))
            assertEquals(c["text"], UsageLog.encodeReport(report))
            assertEquals(report, UsageLog.decodeReport(UsageLog.encodeReport(report)))
        }
        for (raw in data["reportDecode"].list()) {
            val c = raw.obj()
            val decoded = UsageLog.decodeReport(c["text"] as String)
            val e = c["expected"]
            if (e == null) {
                assertNull(c["name"] as String, decoded)
            } else {
                val v = e.list()
                assertEquals(c["name"] as String, UsageReport(v[0].long(), UsageTotals(v[1].int(), v[2].int(), v[3].long())), decoded)
            }
        }
        assertNull(UsageLog.decodeReport(null))
    }
}
