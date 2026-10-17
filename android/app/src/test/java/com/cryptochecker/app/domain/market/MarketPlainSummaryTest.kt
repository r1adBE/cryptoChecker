package com.cryptochecker.app.domain.market

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.app.ui.theme.MarketScalePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Kurz gesagt» im Markt-Tab: gemeinsame Fälle `market_plain_summary.json` (iOS: MarketPlainSummaryTests). */
class MarketPlainSummaryTest {

    private val fixture = ParityJson.load("market_plain_summary.json")
    private val now = fixture["now"].num()!!.toLong()

    @Test
    fun sharedCases() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 40)
        cases.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val actual = MarketPlainSummary.evaluate(input(c["input"].obj()))
            val expected = c["expected"].obj()

            val direction = expected["direction"]?.obj()
            assertEquals("direction: $name", direction?.get("kind"), actual.direction?.kind?.name)
            direction?.let { assertEquals("direction %: $name", it["changePercent"].num()!!, actual.direction!!.changePercent, 1e-9) }

            val mood = expected["mood"]?.obj()
            assertEquals("mood: $name", mood?.get("kind"), actual.mood?.kind?.name)
            mood?.let { assertEquals("mood value: $name", it["value"].num()!!.toInt(), actual.mood!!.value) }

            val standout = expected["standout"]?.obj()
            assertEquals("standout: $name", standout?.get("kind"), actual.standout?.kind?.name)
            standout?.let {
                val s = actual.standout!!
                assertEquals("coin: $name", it["coin"], s.coin)
                assertEquals("change: $name", it["change24h"].num(), s.change24h)
                assertEquals("btc: $name", it["btc24h"].num(), s.btc24h)
                assertEquals("ratio: $name", it["volumeRatio"].num(), s.volumeRatio)
            }
            assertEquals("isEmpty: $name", direction == null && mood == null && standout == null, actual.isEmpty)
        }
    }

    @Test
    fun moodBandsMatchFearGreedCard() {
        // Satz und Farbstufe der Karte dürfen nie auseinanderlaufen
        (0..100).forEach { value ->
            assertEquals("value $value", MarketScalePalette.level(value), MarketPlainSummary.moodKind(value).ordinal)
        }
    }

    @Test
    fun staleStampOrMissingStampHidesSentence() {
        assertNull(MarketPlainSummary.direction(PlainSummaryInput(now = now, pulseMarketChange24h = 2.0)))
        val old = DataStamp(provider = null, savedAt = now - 16 * 60_000L, ttlMillis = 5 * 60_000L)
        assertNull(MarketPlainSummary.direction(PlainSummaryInput(now = now, pulseMarketChange24h = 2.0, pulseStamp = old)))
    }

    private fun stamp(raw: Any?): DataStamp? = raw?.obj()?.let {
        DataStamp(provider = null, savedAt = now - it["ageMillis"].num()!!.toLong(), ttlMillis = it["ttlMillis"].num()!!.toLong())
    }

    private fun input(i: Map<String, Any?>): PlainSummaryInput = PlainSummaryInput(
        now = now,
        pulseMarketChange24h = i["pulseMarketChange24h"].num(),
        pulseStamp = stamp(i["pulseStamp"]),
        globalMarketChange24h = i["globalMarketChange24h"].num(),
        globalStamp = stamp(i["globalStamp"]),
        fearGreed = i["fearGreed"].num()?.toInt(),
        fearGreedTime = i["fearGreedTimeAgeMillis"].num()?.let { now - it.toLong() },
        fearGreedStamp = stamp(i["fearGreedStamp"]),
        unusual = i["unusual"]?.obj()?.let { report(it) },
        unusualStamp = stamp(i["unusualStamp"]),
    )

    private fun report(r: Map<String, Any?>): UnusualReport = UnusualReport(
        rows = r["rows"].list().map { raw ->
            val row = raw.obj()
            UnusualRow(
                symbol = row["symbol"] as String,
                name = row["name"] as String,
                change24h = row["change24h"].num()!!,
                facts = row["facts"].list().map { f ->
                    val fact = f.obj()
                    UnusualFact(
                        kind = UnusualFactKind.valueOf(fact["kind"] as String),
                        strength = fact["strength"].num()!!,
                        volumeRatio = fact["volumeRatio"].num(),
                        fundingPercent = fact["fundingPercent"].num(),
                    )
                },
                score = 1.0,
            )
        },
        btc24h = r["btc24h"].num(),
        time = now,
    )
}
