package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ChangeBasisTest {

    private val hour = ChangeBasisMath.HOUR_MILLIS
    private fun t(iso: String): Long = Instant.parse(iso).toEpochMilli()
    private val zurich = ZoneId.of("Europe/Zurich")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `names round trip and unknown falls back to rolling`() {
        ChangeBasis.entries.forEach { assertEquals(it, ChangeBasis.fromName(it.name)) }
        assertEquals(ChangeBasis.ROLLING_24H, ChangeBasis.fromName(null))
        assertEquals(ChangeBasis.ROLLING_24H, ChangeBasis.fromName("WEEK"))
        assertFalse(ChangeBasis.ROLLING_24H.isDay)
        assertTrue(ChangeBasis.UTC_DAY.isDay)
        assertTrue(ChangeBasis.LOCAL_DAY.isDay)
    }

    @Test
    fun `utc day starts at midnight utc whatever the zone`() {
        val now = t("2026-10-07T23:59:00Z")
        assertEquals(t("2026-10-07T00:00:00Z"), ChangeBasisMath.dayStart(ChangeBasis.UTC_DAY, now, zurich))
        assertEquals(t("2026-10-08T00:00:00Z"), ChangeBasisMath.dayStart(ChangeBasis.UTC_DAY, t("2026-10-08T00:00:00Z"), zurich))
        assertNull(ChangeBasisMath.dayStart(ChangeBasis.ROLLING_24H, now, zurich))
    }

    @Test
    fun `local day follows the device zone`() {
        // 01:30 am 8. Oktober in Zürich (Sommerzeit, UTC+2) ist in UTC noch der 7. Oktober
        val now = t("2026-10-07T23:30:00Z")
        assertEquals(t("2026-10-07T22:00:00Z"), ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, now, zurich))
        assertEquals(t("2026-10-07T00:00:00Z"), ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, now, utc))
    }

    @Test
    fun `local day on the spring forward day is 23 hours`() {
        // 29.3.2026: 02:00 → 03:00 in Zürich; Tagesbeginn noch in Winterzeit (UTC+1)
        val start = ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, t("2026-03-29T21:00:00Z"), zurich)!!
        assertEquals(t("2026-03-28T23:00:00Z"), start)
        val next = ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, t("2026-03-29T22:30:00Z"), zurich)!!
        assertEquals(t("2026-03-29T22:00:00Z"), next)
        assertEquals(23 * hour, next - start)
    }

    @Test
    fun `local day on the fall back day is 25 hours and still found in 26 candles`() {
        // 25.10.2026: 03:00 → 02:00 in Zürich; Tag beginnt um 00:00 Sommerzeit (UTC+2)
        val now = t("2026-10-25T22:30:00Z") // 23:30 Winterzeit, letzte Stunde des 25-h-Tags
        val start = ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, now, zurich)!!
        assertEquals(t("2026-10-24T22:00:00Z"), start)
        assertEquals(t("2026-10-25T23:00:00Z") - start, 25 * hour)
        // 26 Stundenkerzen bis zur laufenden enthalten den Tagesbeginn, 24 nicht mehr
        val last = ChangeBasisMath.hourOf(now)
        val opens26 = (0 until ChangeBasisMath.CANDLES).associate { (last - it * hour) to 100.0 + it }
        val opens24 = (0 until ChangeBasisMath.ROLLING_CANDLES).associate { (last - it * hour) to 100.0 + it }
        assertEquals(124.0, ChangeBasisMath.openAt(opens26, start)!!, 0.0)
        assertNull(ChangeBasisMath.openAt(opens24, start))
    }

    @Test
    fun `local day where midnight does not exist starts at the first valid time`() {
        // Chile stellt um 24:00 auf Sommerzeit um: 00:00 fehlt an diesem Tag
        val santiago = ZoneId.of("America/Santiago")
        for (day in 1..30) {
            val date = LocalDate.of(2026, 9, day)
            val noon = date.atTime(12, 0).atZone(santiago).toInstant().toEpochMilli()
            val start = ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, noon, santiago)!!
            val local = Instant.ofEpochMilli(start).atZone(santiago)
            assertEquals(date, local.toLocalDate())
            assertTrue(local.toLocalTime() <= LocalTime.of(1, 0))
            assertTrue(start <= noon)
        }
    }

    @Test
    fun `half hour zones use the candle that contains the start`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val start = ChangeBasisMath.dayStart(ChangeBasis.LOCAL_DAY, t("2026-10-07T10:00:00Z"), kolkata)!!
        assertEquals(t("2026-10-06T18:30:00Z"), start)
        assertEquals(t("2026-10-06T18:00:00Z"), ChangeBasisMath.hourOf(start))
        val opens = mapOf(t("2026-10-06T18:00:00Z") to 50.0, t("2026-10-06T19:00:00Z") to 51.0)
        assertEquals(50.0, ChangeBasisMath.openAt(opens, start)!!, 0.0)
    }

    @Test
    fun `day just started uses the running candle open`() {
        val start = t("2026-10-07T00:00:00Z")
        val opens = mapOf(start - hour to 99.0, start to 100.0)
        val ref = ChangeBasisMath.reference(opens, lastClose = 101.0, dayStart = start)!!
        assertEquals(100.0, ref.open, 0.0)
        assertEquals(2.0, DayChange.fromPrice(102.0, ref)!!, 1e-9)
        // Kerze fehlt oder ungültig: kein Bezug («—»)
        assertNull(ChangeBasisMath.reference(mapOf(start - hour to 99.0), 101.0, start))
        assertNull(ChangeBasisMath.reference(mapOf(start to Double.NaN), 101.0, start))
        assertNull(ChangeBasisMath.reference(opens, null, start))
    }

    @Test
    fun `day bases never use the rolling ticker value`() {
        assertEquals(5.0, ChangeBasisMath.choose(ChangeBasis.ROLLING_24H, 5.0) { 1.0 }!!, 0.0)
        assertEquals(1.0, ChangeBasisMath.choose(ChangeBasis.ROLLING_24H, null) { 1.0 }!!, 0.0)
        assertEquals(1.0, ChangeBasisMath.choose(ChangeBasis.UTC_DAY, 5.0) { 1.0 }!!, 0.0)
        assertNull(ChangeBasisMath.choose(ChangeBasis.LOCAL_DAY, 5.0) { null })
        assertNull(ChangeBasisMath.choose(ChangeBasis.LOCAL_DAY, 5.0) { Double.NaN })
        assertTrue(ChangeBasisMath.needsCandles(ChangeBasis.UTC_DAY, 5.0))
        assertFalse(ChangeBasisMath.needsCandles(ChangeBasis.ROLLING_24H, 5.0))
        assertTrue(ChangeBasisMath.needsCandles(ChangeBasis.ROLLING_24H, null))
    }

    @Test
    fun `stamp decides whether stored values still apply`() {
        val now = t("2026-10-07T12:00:00Z")
        // Ohne Stempel: Werte von vorher, also rollend
        assertTrue(ChangeBasisMath.isCurrent(null, ChangeBasis.ROLLING_24H, now, utc))
        assertFalse(ChangeBasisMath.isCurrent(null, ChangeBasis.UTC_DAY, now, utc))
        val today = ChangeBasisMath.stamp(ChangeBasis.UTC_DAY, now, utc)
        assertEquals(t("2026-10-07T00:00:00Z"), today.dayStart)
        assertTrue(ChangeBasisMath.isCurrent(today, ChangeBasis.UTC_DAY, now, utc))
        // Neuer Tag oder andere Basis: «—» bis neu gerechnet
        assertFalse(ChangeBasisMath.isCurrent(today, ChangeBasis.UTC_DAY, t("2026-10-08T00:00:01Z"), utc))
        assertFalse(ChangeBasisMath.isCurrent(today, ChangeBasis.LOCAL_DAY, now, zurich))
        assertFalse(ChangeBasisMath.isCurrent(today, ChangeBasis.ROLLING_24H, now, utc))
        assertTrue(ChangeBasisMath.isCurrent(ChangeBasisMath.stamp(ChangeBasis.ROLLING_24H, now), ChangeBasis.ROLLING_24H, now + 3 * 24 * hour, utc))
        val view = ChangeView.of(today, ChangeBasis.UTC_DAY, t("2026-10-08T01:00:00Z"), utc)
        assertNull(view.shown(2.0))
        assertEquals(2.0, ChangeView.of(today, ChangeBasis.UTC_DAY, now, utc).shown(2.0)!!, 0.0)
    }

    @Test
    fun `stamp text round trip`() {
        val stamp = ChangeStamp(ChangeBasis.LOCAL_DAY, 1_760_000_000_000L)
        assertEquals(stamp, ChangeStamp.decode(stamp.encode()))
        assertNull(ChangeStamp.decode(null))
        assertNull(ChangeStamp.decode("LOCAL_DAY"))
        assertNull(ChangeStamp.decode("WEEK@1"))
        assertNull(ChangeStamp.decode("UTC_DAY@x"))
    }

    @Test
    fun `chart today starts at the day start candle`() {
        val start = t("2026-10-07T00:00:00Z")
        val times = (0 until 24).map { start - 10 * hour + it * hour }
        val since = ChangeBasisMath.sinceDayStart(times, start) { it }
        assertEquals(start, since.first())
        assertEquals(14, since.size)
        // Tag gerade begonnen: nur die laufende Kerze → die letzten zwei
        val early = (0 until 24).map { start - 23 * hour + it * hour }
        assertEquals(listOf(start - hour, start), ChangeBasisMath.sinceDayStart(early, start) { it })
    }

    @Test
    fun `fixed zones like Binance, rolling and device first`() {
        val all = ChangeBasis.entries
        assertEquals(2 + 27, all.size)
        assertEquals(ChangeBasis.ROLLING_24H, all[0])
        assertEquals(ChangeBasis.LOCAL_DAY, all[1])
        assertEquals(ChangeBasis.utc(14), all[2])
        assertEquals(ChangeBasis.utc(-12), all.last())
        assertEquals(ChangeBasis.UTC_DAY, ChangeBasis.utc(0))
        assertNull(ChangeBasis.utc(15))
        assertNull(ChangeBasis.utc(-13))
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `fixed zone names round trip`() {
        assertEquals("UTC_DAY", ChangeBasis.UTC_DAY.name)
        assertEquals("UTC_DAY+8", ChangeBasis.utc(8)!!.name)
        assertEquals("UTC_DAY-5", ChangeBasis.utc(-5)!!.name)
        assertEquals(ChangeBasis.utc(-5), ChangeBasis.fromName("UTC_DAY-5"))
        assertEquals(ChangeBasis.ROLLING_24H, ChangeBasis.fromName("UTC_DAY+0"))
        assertEquals(ChangeBasis.ROLLING_24H, ChangeBasis.fromName("UTC_DAY+99"))
        val stamp = ChangeStamp(ChangeBasis.utc(-5)!!, 1_760_000_000_000L)
        assertEquals(stamp, ChangeStamp.decode(stamp.encode()))
    }

    @Test
    fun `fixed zone day start ignores device zone and summer time`() {
        val now = t("2026-10-07T23:59:00Z")
        val plus8 = ChangeBasis.utc(8)!!
        assertEquals(t("2026-10-07T16:00:00Z"), ChangeBasisMath.dayStart(plus8, now, zurich))
        assertEquals(t("2026-10-07T16:00:00Z"), ChangeBasisMath.dayStart(plus8, now, utc))
        assertEquals(t("2026-10-06T05:00:00Z"), ChangeBasisMath.dayStart(ChangeBasis.utc(-5)!!, t("2026-10-07T03:00:00Z"), zurich))
        // Jede feste Zone: Tagesbeginn höchstens 24 h zurück, liegt in den geladenen Kerzen
        ChangeBasis.entries.filter { it.kind == ChangeBasis.Kind.UTC_DAY }.forEach { b ->
            val start = ChangeBasisMath.dayStart(b, now, zurich)!!
            assertTrue(b.name, start <= now && now - start < ChangeBasisMath.DAY_MILLIS)
            assertTrue(b.name, now - ChangeBasisMath.hourOf(start) < ChangeBasisMath.CANDLES * hour)
        }
    }

    @Test
    fun `zone labels`() {
        assertEquals("UTC", ChangeBasisMath.zoneLabel(0))
        assertEquals("UTC+8", ChangeBasisMath.zoneLabel(ChangeBasis.utc(8)!!))
        assertEquals("UTC-12", ChangeBasisMath.zoneLabel(ChangeBasis.utc(-12)!!))
        assertNull(ChangeBasisMath.zoneLabel(ChangeBasis.LOCAL_DAY))
        assertEquals("UTC+2", ChangeBasisMath.deviceZoneLabel(t("2026-07-01T12:00:00Z"), zurich))
        assertEquals("UTC+1", ChangeBasisMath.deviceZoneLabel(t("2026-12-01T12:00:00Z"), zurich))
        assertEquals("UTC+5:30", ChangeBasisMath.deviceZoneLabel(t("2026-12-01T12:00:00Z"), ZoneId.of("Asia/Kolkata")))
    }

    @Test
    fun `cache keeps utc, local and the chosen zone`() {
        val now = t("2026-10-07T12:00:00Z")
        val kept = ChangeBasisMath.keptDayStarts(ChangeBasis.utc(8)!!, now, zurich)
        assertEquals(listOf(t("2026-10-07T00:00:00Z"), t("2026-10-06T22:00:00Z"), t("2026-10-06T16:00:00Z")), kept)
        assertEquals(2, ChangeBasisMath.keptDayStarts(ChangeBasis.ROLLING_24H, now, zurich).size)
        assertEquals(2, ChangeBasisMath.keptDayStarts(ChangeBasis.UTC_DAY, now, zurich).size)
    }
}
