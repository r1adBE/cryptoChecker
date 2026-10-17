package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.DepegAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `depeg_alarm.json` (iOS: `DepegAlarmParityTests.swift`). */
class DepegAlarmParityTest {

    private val data = ParityJson.load("depeg_alarm.json")

    @Test
    fun threshold() {
        data["threshold"].list().map { it.obj() }.forEach { c ->
            assertEquals("threshold: $c", c["expected"].num()?.toInt(), DepegAlarm.threshold(c["stored"].num()!!.toInt()))
        }
    }

    @Test
    fun combine() {
        data["combine"].list().map { it.obj() }.forEach { c ->
            val price = DepegAlarm.combine(DepegAlarm.fromKraken(c["kraken"].num()), DepegAlarm.fromBinanceUsdc(c["binance"].num()))
            val expected = c["expected"].num()
            if (expected == null) {
                assertNull("combine: $c", price)
            } else {
                assertEquals("combine: $c", expected, price!!, 1e-9)
            }
        }
    }

    @Test
    fun evaluate() {
        val cases = data["evaluate"].list().map { it.obj() }
        assertTrue(cases.size >= 8)
        cases.forEach { c ->
            val d = DepegAlarm.evaluate(
                c["deviation"].num()!!, c["threshold"].num()!!, c["armed"] as Boolean, c["mayFire"] as Boolean
            )
            assertEquals("fire: $c", c["fire"], d.fire)
            assertEquals("armed: $c", c["armedAfter"], d.armed)
        }
    }

    @Test
    fun episodes() {
        data["episodes"].list().map { it.obj() }.forEach { e ->
            val threshold = DepegAlarm.thresholdPercent(e["thresholdTenths"].num()!!.toInt())
            val quiet = e["quiet"].list().map { it as Boolean }
            var armed = true
            val fired = e["prices"].list().mapIndexed { i, p ->
                val d = DepegAlarm.evaluate(DepegAlarm.deviationPercent(p.num()!!), threshold, armed, !quiet[i])
                armed = DepegAlarm.armedAfter(d.fire, posted = d.fire, armedBefore = armed, next = d.armed)
                d.fire
            }
            assertEquals("episode ${e["name"]}", e["fired"].list(), fired)
        }
    }

    /** Warnzeile der Portfolio-Karte: frische Messung ≥ Schwelle (30 Min gültig). */
    @Test
    fun cardWarning() {
        val now = 1_800_000_000_000L
        val cases = data["cardWarning"].list().map { it.obj() }
        assertTrue(cases.size >= 10)
        cases.forEach { c ->
            val reading = c["price"].num()?.let { DepegAlarm.Reading(it, now - c["ageMillis"].num()!!.toLong()) }
            val shown = DepegAlarm.cardWarning(reading, c["thresholdTenths"].num()!!.toInt(), now)
            assertEquals("cardWarning: $c", c["shown"], shown != null)
            if (shown != null) assertEquals(reading, shown)
        }
    }

    /** Build 58: Zeile «USDT = 1» der Karte unabhängig vom Alarm (Bestand mit USDT, Werte über USDT = USD, Abweichung). */
    @Test
    fun cardNote() {
        val now = 1_800_000_000_000L
        val cases = data["cardNote"].list().map { it.obj() }
        assertTrue(cases.size >= 10)
        cases.forEach { c ->
            val reading = c["price"].num()?.let { DepegAlarm.Reading(it, now - c["ageMillis"].num()!!.toLong()) }
            val note = DepegAlarm.cardNote(
                c["holdings"].list().map { it as String }, c["valuesViaUsdt"] as Boolean, reading,
                c["thresholdTenths"].num()!!.toInt(), now,
            )
            assertEquals("cardNote: $c", c["kind"], note?.kind?.name)
            assertEquals("cardNote reading: $c", if (note?.kind == DepegAlarm.CardNoteKind.DEVIATION) reading else null, note?.reading)
        }
    }

    @Test
    fun notPostedKeepsArmed() {
        assertTrue(DepegAlarm.armedAfter(fire = true, posted = false, armedBefore = true, next = false))
    }

    @Test
    fun mainExchange() {
        data["mainExchange"].list().map { it.obj() }.forEach { c ->
            val keys = c["keys"].list().map { it as String }
            assertEquals("main: $c", c["expected"], DepegAlarm.mainExchange(keys))
            assertEquals("url: $c", c["url"], DepegAlarm.exchangeUrl(keys))
        }
    }

    @Test
    fun urlsAreHttps() {
        DepegAlarm.EXCHANGE_URLS.forEach { assertTrue(it.second, it.second.startsWith("https://")) }
        assertTrue(DepegAlarm.EXCHANGE_URLS.any { it.first == DepegAlarm.FALLBACK_EXCHANGE })
    }
}
