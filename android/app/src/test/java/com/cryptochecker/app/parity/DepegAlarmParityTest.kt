package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.DepegAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Drei Quellen, Einbruch nur bestätigt, USDC/USDT allein nie alarmfähig. */
    @Test
    fun measure() {
        val cases = data["measure"].list().map { it.obj() }
        assertTrue(cases.size >= 15)
        cases.forEach { c ->
            val m = DepegAlarm.measure(c["kraken"].num(), c["coinbase"].num(), c["binance"].num())
            val expected = c["expected"].num()
            if (expected == null) {
                assertNull("measure: $c", m)
            } else {
                assertEquals("measure: $c", expected, m!!.price, 1e-9)
                assertEquals("measure viaUsdc: $c", c["viaUsdc"], m.viaUsdc)
                assertEquals("mayAlarm: $c", !m.viaUsdc, DepegAlarm.mayAlarm(m))
                assertEquals("broken: $c", expected < 0.5 || expected > 1.5, m.broken)
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
            val reading = c["price"].num()?.let {
                DepegAlarm.Reading(it, now - c["ageMillis"].num()!!.toLong(), viaUsdc = c["viaUsdc"] as? Boolean ?: false)
            }
            val note = DepegAlarm.cardNote(
                c["holdings"].list().map { it as String }, c["valuesViaUsdt"] as Boolean, reading,
                c["thresholdTenths"].num()!!.toInt(), now, usdtMarket = c["usdtMarket"] as? Boolean ?: false,
            )
            assertEquals("cardNote: $c", c["kind"], note?.kind?.name)
            val withReading = note?.kind in setOf(
                DepegAlarm.CardNoteKind.DEVIATION, DepegAlarm.CardNoteKind.USDT_BREAK, DepegAlarm.CardNoteKind.VIA_USDC,
            )
            assertEquals("cardNote reading: $c", if (withReading) reading else null, note?.reading)
        }
    }

    /** Live-Kurs USDT/USD der Bewertung aus der letzten Messung (USDT zum Marktkurs, Owner-Entscheid Okt. 2026; 6 Std. gültig). */
    @Test
    fun liveUsdtUsd() {
        val now = 1_800_000_000_000L
        // 6 Std. statt der 30 Min. der Warnzeile (lange Hintergrund-Intervalle)
        assertEquals(21_600_000L, DepegAlarm.LIVE_MAX_AGE_MILLIS)
        assertEquals(1_800_000L, DepegAlarm.CARD_MAX_AGE_MILLIS)
        val cases = data["liveUsdtUsd"].list().map { it.obj() }
        assertTrue(cases.size >= 5)
        cases.forEach { c ->
            val reading = c["price"].num()?.let { DepegAlarm.Reading(it, now - c["ageMillis"].num()!!.toLong()) }
            val live = DepegAlarm.liveUsdtUsd(reading, now)
            val expected = c["expected"].num()
            if (expected == null) assertNull("liveUsdtUsd: $c", live) else assertEquals("liveUsdtUsd: $c", expected, live!!, 1e-12)
        }
    }

    /** Messen mit Alarm oder Portfolio (USDT zum Marktkurs braucht den Live-Kurs auch ohne Alarm). Wie iOS. */
    @Test
    fun shouldMeasure() {
        assertTrue(DepegAlarm.shouldMeasure(depegAlarm = true, portfolioEnabled = false, hasPortfolioData = false))
        assertTrue(DepegAlarm.shouldMeasure(depegAlarm = false, portfolioEnabled = true, hasPortfolioData = true))
        assertTrue(DepegAlarm.shouldMeasure(depegAlarm = true, portfolioEnabled = true, hasPortfolioData = false))
        // Portfolio aus oder leer: ohne Alarm keine Anfrage
        assertFalse(DepegAlarm.shouldMeasure(depegAlarm = false, portfolioEnabled = true, hasPortfolioData = false))
        assertFalse(DepegAlarm.shouldMeasure(depegAlarm = false, portfolioEnabled = false, hasPortfolioData = true))
        assertFalse(DepegAlarm.shouldMeasure(depegAlarm = false, portfolioEnabled = false, hasPortfolioData = false))
        // Fiat-Alarm bzw. ≈ Umrechnung über USDT: auch ohne Alarm und Portfolio messen
        assertTrue(DepegAlarm.shouldMeasure(depegAlarm = false, portfolioEnabled = false, hasPortfolioData = false, fiatConversion = true))
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
