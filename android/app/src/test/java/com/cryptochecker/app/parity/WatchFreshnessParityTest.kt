package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.marketdata.watch.WatchFreshness
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Alter der Kurse auf der Uhr (`watch_quotes.json`, Abschnitt «freshness») — wie `WatchQuotesParityTests.swift`:
 * «veraltet» nach derselben Regel wie Merkliste und Widgets (`OutdatedRule` mit dem 15-Min.-Takt der Komplikation)
 * und Alter in Minuten, Stunden, Tagen.
 */
class WatchFreshnessParityTest {

    private val data = ParityJson.load("watch_quotes.json").getValue("freshness").obj()

    @Test
    fun sameRuleAsTheApp() {
        assertEquals(data["updateMinutes"].num()!!.toInt(), WatchFreshness.UPDATE_MINUTES)
        assertEquals(data["outdatedAfterMillis"].num()!!.toLong(), WatchFreshness.OUTDATED_AFTER_MILLIS)
        assertEquals(OutdatedRule.afterMillis(WatchFreshness.UPDATE_MINUTES), WatchFreshness.OUTDATED_AFTER_MILLIS)
    }

    @Test
    fun cases() {
        val now = data["now"].num()!!.toLong()
        for (c in data.getValue("cases").list().map { it.obj() }) {
            val name = c["name"] as String
            val time = c["time"].num()!!.toLong()
            assertEquals(name, c["outdated"] as Boolean, WatchFreshness.isOutdated(time, now))
            assertEquals(name, OutdatedRule.isOutdated(time, now, WatchFreshness.OUTDATED_AFTER_MILLIS),
                WatchFreshness.isOutdated(time, now))
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
