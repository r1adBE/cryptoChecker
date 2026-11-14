package com.cryptochecker.app.domain.refresh
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchInterval
import org.junit.Assert.assertEquals
import org.junit.Test
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj

/**
 * Alter der Kurse auf der Uhr (`watch_quotes.json`, Abschnitt «freshness») — wie `WatchQuotesParityTests.swift`:
 * «veraltet» nach derselben Regel wie Merkliste und Widgets (`OutdatedRule` mit dem Intervall des Handys, 3 × Intervall)
 * und Alter in Minuten, Stunden, Tagen.
 */
class WatchFreshnessParityTest {

    private val data = ParityJson.load("watch_quotes.json").getValue("freshness").obj()

    @Test
    fun sameRuleAsTheApp() {
        assertEquals(data["updateMinutes"].num()!!.toInt(), WatchFreshness.UPDATE_MINUTES)
        // Grenze = 3 × Intervall des Handys (begrenzt) — dieselbe Regel wie Merkliste und Widgets
        for (i in data.getValue("intervals").list().map { it.obj() }) {
            val interval = i["interval"].num()!!.toInt()
            val after = i["afterMillis"].num()!!.toLong()
            assertEquals("interval $interval", after, WatchFreshness.outdatedAfterMillis(interval))
            assertEquals("interval $interval", OutdatedRule.afterMillis(WatchInterval.clamp(interval)), after)
        }
    }

    @Test
    fun cases() {
        val now = data["now"].num()!!.toLong()
        for (c in data.getValue("cases").list().map { it.obj() }) {
            val name = c["name"] as String
            val time = c["time"].num()!!.toLong()
            val interval = c["interval"].num()!!.toInt()
            assertEquals(name, c["outdated"] as Boolean, WatchFreshness.isOutdated(time, now, interval))
            assertEquals(name, OutdatedRule.isOutdated(time, now, WatchFreshness.outdatedAfterMillis(interval)),
                WatchFreshness.isOutdated(time, now, interval))
            val unit = c["unit"] as String? ?: continue
            val expected = WatchFreshness.Age(
                when (unit) {
                    "justNow" -> WatchFreshness.AgeUnit.JUST_NOW
                    "minutes" -> WatchFreshness.AgeUnit.MINUTES
                    "hours" -> WatchFreshness.AgeUnit.HOURS
                    "days" -> WatchFreshness.AgeUnit.DAYS
                    else -> error("unknown unit $unit")
                },
                c["count"].num()!!.toInt(),
            )
            assertEquals(name, expected, WatchFreshness.age(time, now))
        }
    }
}
