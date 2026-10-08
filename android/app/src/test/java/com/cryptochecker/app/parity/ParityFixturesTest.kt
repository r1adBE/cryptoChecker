package com.cryptochecker.app.parity

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.domain.watch.DayReference
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.util.Change24h
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Gemeinsame Testfälle Android/iOS (JSON-Dateien in `testdata/parity/`): gleiche Eingaben, gleiche
 * erwarteten Ergebnisse. Das iOS-Gegenstück (`Tests/ParityFixtureTests.swift`) liest
 * dieselben Dateien (vom Projekt-Generator ins Testziel kopiert).
 */
class ParityFixturesTest {

    private val evaluator = AlarmEvaluator()

    private fun time(value: Any?): Long = when (value) {
        is String -> Instant.parse(value).toEpochMilli()
        is Number -> value.toLong()
        else -> error("not a time: $value")
    }

    private fun assertNumber(expected: Double?, actual: Double?, tolerance: Double, name: String) {
        if (expected == null) {
            assertNull(name, actual)
        } else {
            assertNotNull(name, actual)
            assertEquals(name, expected, actual!!, tolerance)
        }
    }

    private fun alarm(spec: Map<String, Any?>) = AlarmEntity(
        id = 1,
        watchId = 1,
        condition = AlarmCondition.valueOf(spec["condition"] as String),
        threshold = spec["threshold"].num()!!,
        enabled = spec["enabled"] as? Boolean ?: true,
        repeating = spec["repeating"] as? Boolean ?: false,
        referencePrice = spec["referencePrice"].num(),
        lastTriggeredAt = spec["lastTriggeredAt"].num()?.toLong() ?: 0L,
        windowHours = spec["windowHours"].num()?.toInt() ?: 1,
        referenceAt = spec["referenceAt"].num()?.toLong() ?: 0L,
    )

    // ---------------- Alarme

    @Test
    fun alarms() {
        val data = ParityJson.load("alarms.json")
        val now = data["now"].num()!!.toLong()
        var count = 0

        data["trigger"].list().forEach { raw ->
            val c = raw.obj()
            val result = evaluator.shouldTrigger(
                alarm(c["alarm"].obj()), c["price"].num()!!, c["previousPrice"].num(), now,
                c["cooldownMinutes"].num()!!.toInt(),
            )
            assertEquals("trigger: ${c["name"]}", c["expected"], result)
            count++
        }
        data["rearm"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals("rearm: ${c["name"]}", c["expected"], evaluator.shouldRearmLevel(alarm(c["alarm"].obj()), c["price"].num()!!))
            count++
        }
        data["volumeSpike"].list().forEach { raw ->
            val c = raw.obj()
            val result = evaluator.shouldTriggerVolumeSpike(
                alarm(c["alarm"].obj()), c["ratio"].num(), c["candleOpenTime"].num()!!.toLong(), now,
                c["cooldownMinutes"].num()!!.toInt(),
            )
            assertEquals("volumeSpike: ${c["name"]}", c["expected"], result)
            count++
        }
        data["sequences"].obj()["cases"].list().forEach { raw ->
            val c = raw.obj()
            var a = alarm(c["alarm"].obj())
            var fired = 0
            val step = c["stepMillis"].num()!!.toLong()
            val cooldown = c["cooldownMinutes"].num()!!.toInt()
            c["prices"].list().forEachIndexed { i, p ->
                val price = p.num()!!
                val t = now + i * step
                if (!a.enabled) return@forEachIndexed
                if (evaluator.shouldRearmLevel(a, price)) {
                    a = a.copy(referenceAt = 0)
                    return@forEachIndexed
                }
                if (evaluator.shouldTrigger(a, price, previousPrice = null, now = t, cooldownMinutes = cooldown)) {
                    fired++
                    a = a.copy(lastTriggeredAt = t, lastTriggeredPrice = price, referenceAt = t, enabled = a.repeating)
                }
            }
            assertEquals("sequence: ${c["name"]}", c["expectedFires"].num()!!.toInt(), fired)
            count++
        }
        assertTrue("alarm cases read", count >= 40)
    }

    // ---------------- Schwellwert-Eingabe

    @Test
    fun thresholdParser() {
        val cases = ParityJson.load("threshold.json")["cases"].list()
        cases.forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String
            val decimal = (c["decimal"] as String).single()
            val actual = ThresholdParser.parse(text, decimal, c["hint"].num())
            val expected = c["expected"].num()
            assertNumber(expected, actual, abs(expected ?: 0.0) * 1e-12, "threshold '$text' ($decimal, ${c["hint"]})")
        }
        assertTrue(cases.size >= 40)
    }

    // ---------------- Basis der %-Änderung

    @Test
    fun changeBasis() {
        val data = ParityJson.load("change_basis.json")
        data["dayStart"].list().forEach { raw ->
            val c = raw.obj()
            val basis = basisOf(c["basis"] as String)
            val actual = ChangeBasisMath.dayStart(basis, time(c["now"]), ZoneId.of(c["zone"] as String))
            assertEquals("dayStart: ${c["name"]}", c["expected"]?.let { time(it) }, actual)
        }
        data["choose"].list().forEach { raw ->
            val c = raw.obj()
            val basis = basisOf(c["basis"] as String)
            val candles = c["candles"].num()
            val actual = ChangeBasisMath.choose(basis, c["ticker"].num()) { candles }
            assertNumber(c["expected"].num(), actual, 1e-9, "choose: $c")
        }
        data["isCurrent"].list().forEach { raw ->
            val c = raw.obj()
            val stamp = c["stamp"]?.obj()?.let { ChangeStamp(basisOf(it["basis"] as String), time(it["dayStart"])) }
            val actual = ChangeBasisMath.isCurrent(
                stamp, basisOf(c["basis"] as String), time(c["now"]), ZoneId.of(c["zone"] as String)
            )
            assertEquals("isCurrent: ${c["name"]}", c["expected"], actual)
        }
        data["fromName"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals("fromName: ${c["name"]}", basisOf(c["expected"] as String), ChangeBasis.fromName(c["name"] as String?))
        }
        data["zoneLabel"].list().forEach { raw ->
            val c = raw.obj()
            val seconds = c["offsetSeconds"].num()!!.toInt()
            assertEquals("zoneLabel: $seconds", c["expected"], ChangeBasisMath.zoneLabel(seconds))
        }
    }

    /** Gespeicherter Name einer Basis (auch «UTC_DAY+8»); unbekannt = Fehler im Testfall. */
    private fun basisOf(name: String): ChangeBasis =
        requireNotNull(ChangeBasis.parse(name)) { "unbekannte Basis im Testfall: $name" }

    // ---------------- Nicht mehr gehandelt

    @Test
    fun notTraded() {
        val data = ParityJson.load("not_traded.json")
        assertEquals(NotTraded.MARKER, data["marker"])
        data["cases"].list().forEach { raw ->
            val c = raw.obj()
            val error = c["lastError"] as String?
            assertEquals("isMarker: $error", c["isMarker"], NotTraded.isMarker(error))
            assertNumber(c["shown"].num(), NotTraded.shownChange24h(error, c["change24h"].num()), 0.0, "shown: $error")
        }
    }

    // ---------------- Veraltet (Live-Modus 2 Min., sonst 3 × Intervall)

    @Test
    fun outdated() {
        val data = ParityJson.load("outdated.json")
        val now = data["now"].num()!!.toLong()
        data["afterMillis"].list().forEach { raw ->
            val c = raw.obj()
            val actual = OutdatedRule.afterMillis(
                liveService = c["liveService"] as Boolean,
                liveIntervalSeconds = c["liveIntervalSeconds"].num()!!.toInt(),
                backgroundIntervalMinutes = c["backgroundIntervalMinutes"].num()!!.toInt(),
                live = c["live"] as Boolean,
            )
            assertEquals("afterMillis: ${c["name"]}", c["expected"].num()!!.toLong(), actual)
        }
        data["stale"].list().forEach { raw ->
            val c = raw.obj()
            val time = c["ageMillis"].num()?.let { now - it.toLong() } ?: 0L
            assertEquals(
                "stale: ${c["name"]}", c["expected"],
                OutdatedRule.isOutdated(time, now, c["afterMillis"].num()!!.toLong()),
            )
        }
    }

    // ---------------- 24-h-Veränderung

    private fun reference(value: Any?): DayReference? =
        value?.list()?.let { DayReference(it[0].num()!!, it[1].num()!!) }

    @Test
    fun dayChange() {
        val data = ParityJson.load("day_change.json")
        data["fromTicker"].list().forEach { raw ->
            val c = raw.obj()
            assertNumber(c["expected"].num(), DayChange.fromTicker(c["value"].num()), 0.0, "fromTicker: ${c["value"]}")
        }
        data["select"].list().forEach { raw ->
            val c = raw.obj()
            val actual = DayChange.select(
                c["price"].num(), reference(c["pair"]), reference(c["usdt"]), c["quoteIsFiat"] as Boolean
            )
            assertNumber(c["expected"].num(), actual, 1e-9, "select: ${c["name"]}")
        }
        data["reference"].list().forEach { raw ->
            val c = raw.obj()
            val valid = DayReference.of(c["open"].num(), c["lastClose"].num()) != null
            assertEquals("reference: $c", c["valid"], valid)
        }
        data["candleQuote"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(c["expected"], DayChange.candleQuote(c["quote"] as String))
        }
        data["change24h"].list().forEach { raw ->
            val c = raw.obj()
            val args = c["args"].list().map { it.num()!! }
            val actual = when (val kind = c["kind"] as String) {
                "percent" -> Change24h.percent(args[0])
                "fraction" -> Change24h.fraction(args[0])
                "fromOpen" -> Change24h.fromOpen(args[0], args[1])
                "fromAbsolute" -> Change24h.fromAbsolute(args[0], args[1])
                else -> error("unknown kind $kind")
            }
            assertNumber(c["expected"].num(), actual, 1e-9, "change24h: $c")
        }
    }
}
