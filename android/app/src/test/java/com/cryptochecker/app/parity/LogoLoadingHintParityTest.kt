package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.LogoLoadingHint
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hinweis «Logos werden geladen …» ([LogoLoadingHint]): gemeinsame Testfälle `coin_logos.json` › `loadingHint` (iOS:
 * `LogoLoadingHintTests.swift`), Ende des Durchgangs auch bei Fehler, und die Anbindung im Quelltext — Durchgang um
 * die Stufen von `CoinListSync`, fehlende Bilder aus `LogoImageStore.downloadMissing`, Zeile nur mit Logo-Quelle
 * (Schalter «Coin-Logos» aus → nie); Fortschritt «· 40 %» nach `coin_logos.json` › `loadingPercent`. Am echten
 * Bild-Abruf: `LogoLoadingHintDeviceTest`, die Zeile: `MissingLogosHintTest` (Gerät).
 */
class LogoLoadingHintParityTest {

    private val fixture = ParityJson.load("coin_logos.json")["loadingHint"].obj()

    @Test
    fun markerAsFixture() {
        assertEquals(fixture["marker"] as String, LogoLoadingHint.DONE_MARKER)
    }

    @Test
    fun casesAsFixture() {
        val cases = fixture["cases"].list().map { it.obj() }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val hint = LogoLoadingHint()
            val steps = case["steps"].list().map { it as String }
            val expected = case["visible"].list().map { it as Boolean }
            assertEquals(case["name"] as String, expected.size, steps.size)
            var markDone = false
            steps.forEachIndexed { i, step ->
                when {
                    step == "start" -> hint.runStarted(firstLoadDone = false)
                    step == "start done" -> hint.runStarted(firstLoadDone = true)
                    step == "finish" -> if (hint.runFinished()) markDone = true
                    step == "finish incomplete" -> if (hint.runFinished(complete = false)) markDone = true
                    step.startsWith("pending ") -> hint.imagesPending(step.removePrefix("pending ").toInt())
                    else -> throw AssertionError("unbekannter Schritt $step")
                }
                assertEquals("${case["name"]} › $step", expected[i], hint.visible.value)
            }
            assertEquals("${case["name"]} › markDone", case["markDone"] as Boolean, markDone)
        }
    }

    private val percentFixture = ParityJson.load("coin_logos.json")["loadingPercent"].obj()

    /** Ein Schritt der Testfälle («start», «start done», «pending N [last]», «done N», «paused», «finish»). */
    private fun LogoLoadingHint.step(step: String): Boolean {
        val parts = step.split(" ")
        when (parts[0]) {
            "start" -> runStarted(firstLoadDone = parts.getOrNull(1) == "done")
            "finish" -> return runFinished()
            "pending" -> imagesPending(parts[1].toInt(), last = parts.getOrNull(2) == "last")
            "done" -> repeat(parts[1].toInt()) { imageDone() }
            "paused" -> imagesPaused()
            else -> throw AssertionError("unbekannter Schritt $step")
        }
        return false
    }

    @Test
    fun percentCasesAsFixture() {
        val cases = percentFixture["cases"].list().map { it.obj() }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val hint = LogoLoadingHint()
            val steps = case["steps"].list().map { it as String }
            val expected = case["percent"].list().map { it.num()?.toInt() }
            assertEquals(case["name"] as String, expected.size, steps.size)
            steps.forEachIndexed { i, step ->
                hint.step(step)
                assertEquals("${case["name"]} › $step", expected[i], hint.currentPercent)
            }
        }
    }

    @Test
    fun percentRuleAndThrottleAsFixture() {
        assertEquals(percentFixture["intervalMillis"].num()!!.toLong(), LogoLoadingHint.SHOW_INTERVAL_MILLIS)
        assertEquals(percentFixture["step"].num()!!.toInt(), LogoLoadingHint.SHOW_STEP)
        for (row in percentFixture["percentOf"].list().map { r -> r.list().map { it.num()!!.toInt() } }) {
            assertEquals("percentOf $row", row[2], LogoLoadingHint.percentOf(row[0], row[1]))
        }
        for (c in percentFixture["show"].list().map { it.obj() }) {
            val elapsed = c["elapsed"].num()!!.toLong()
            val got = LogoLoadingHint.shouldShow(c["shown"].num()?.toInt(), 10_000L, c["next"].num()?.toInt(), 10_000L + elapsed)
            assertEquals("show $c", c["show"] as Boolean, got)
        }
    }

    /** Angezeigter Wert gedrosselt (nicht je Bild), am Ende wieder ohne Prozent. */
    @Test
    fun shownPercentIsThrottled() {
        var now = 50_000L
        val hint = LogoLoadingHint(clock = { now })
        hint.runStarted(firstLoadDone = false)
        hint.imagesPending(100)
        assertEquals(null, hint.percent.value)
        hint.imagesPending(100, last = true)
        assertEquals(0, hint.percent.value)
        hint.imageDone()
        assertEquals(0, hint.percent.value)
        now += 1_000
        hint.imageDone()
        assertEquals(2, hint.percent.value)
        now += 100
        repeat(5) { hint.imageDone() }
        assertEquals(7, hint.percent.value)
        // Am Ende bleibt der Wert, solange die Zeile ausblendet; der nächste Durchgang beginnt ohne
        assertTrue(hint.runFinished())
        assertFalse(hint.visible.value)
        assertEquals(7, hint.percent.value)
        hint.runStarted(firstLoadDone = false)
        assertEquals(null, hint.percent.value)
        hint.imagesPending(4, last = true)
        hint.imageDone()
        assertEquals(25, hint.percent.value)
        // Server bremst: sofort ohne Prozent
        hint.imagesPaused()
        assertEquals(null, hint.percent.value)
    }

    /** Merk-Datei im Logo-Ordner: nach dem ersten Laden kein Hinweis mehr; fehlt sie (Zwischenspeicher geleert), wieder. */
    @Test
    fun firstLoadIsRememberedInTheLogoFolder() {
        val dir = Files.createTempDirectory("logos").toFile()
        try {
            val marker = File(dir, LogoLoadingHint.DONE_MARKER)
            val hint = LogoLoadingHint(doneMarker = { marker })
            fun run(): Boolean {
                var shown = false
                runBlocking {
                    hint.during {
                        hint.imagesPending(3)
                        shown = hint.visible.value
                    }
                }
                return shown
            }
            assertTrue(run())
            assertTrue(marker.isFile)
            // Tägliches Nachholen gescheiterter Bilder: kein Hinweis
            assertFalse(run())
            // Zwischenspeicher geleert (Merk-Datei weg): erstes Laden von vorn
            marker.delete()
            assertTrue(run())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun runEndsAlsoOnFailure() {
        // Ohne Merk-Datei: jeder Durchgang gilt als erstes Laden
        val hint = LogoLoadingHint()
        runCatching {
            runBlocking {
                hint.during {
                    hint.imagesPending(4)
                    assertTrue(hint.visible.value)
                    error("Netz weg")
                }
            }
        }
        assertFalse(hint.visible.value)
        // Danach zählt kein Bild mehr ohne neuen Durchgang
        hint.imagesPending(4)
        assertFalse(hint.visible.value)
    }
}
