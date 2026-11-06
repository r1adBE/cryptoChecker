package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.LogoLoadingHint
import com.cryptochecker.app.util.SourceGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Hinweis «Logos werden geladen …» ([LogoLoadingHint]): gemeinsame Testfälle `coin_logos.json` › `loadingHint` (iOS:
 * `LogoLoadingHintTests.swift`), Ende des Durchgangs auch bei Fehler, und die Anbindung im Quelltext — Durchgang um
 * die Stufen von `CoinListSync`, fehlende Bilder aus `LogoImageStore.downloadMissing`, Zeile nur mit Logo-Quelle
 * (Schalter «Coin-Logos» aus → nie); Fortschritt «· 40 %» nach `coin_logos.json` › `loadingPercent`.
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

    @Test
    fun wiredToTheDownloadRunAndTheLogoSwitch() {
        val data = SourceGuard.android(
            listOf("../data/src/main/java/com/cryptochecker/app/data", "data/src/main/java/com/cryptochecker/app/data")
                .map(::File).firstOrNull { it.isDirectory },
            "data/src/main/java/com/cryptochecker/app/data",
        )
        val app = SourceGuard.android(
            listOf("src/main/java/com/cryptochecker/app/ui", "app/src/main/java/com/cryptochecker/app/ui")
                .map(::File).firstOrNull { it.isDirectory },
            "app/src/main/java/com/cryptochecker/app/ui",
        )
        val sync = File(data, "CoinListSync.kt").readText()
        val store = File(data, "LogoImageStore.kt").readText()
        assertTrue(store.contains("LogoLoadingHint(doneMarker = { File(imageDir, LogoLoadingHint.DONE_MARKER) })"))
        assertTrue(Regex("""loadingHint\.during \{ for \(stage in stages\) total \+= imageStore\.downloadMissing""").containsMatchIn(sync))
        // Gesamtzahl erst mit der letzten Stufe (nach der ganzen Rangliste); erledigte und gebremste Bilder zählen
        assertTrue(sync.contains("stages.send(CoinLogos.withStockLogos(current, names.tradFiBases) to true)"))
        assertTrue(store.contains("loadingHint.imagesPending(pending.size, last = lastStage)"))
        assertTrue(Regex("""Fetched\.OK -> \{\s+loadingHint\.imageDone\(\)""").containsMatchIn(store))
        assertTrue(store.contains("Fetched.FAILED -> newFailures[job.first] = now.also { loadingHint.imageDone() }"))
        assertTrue(store.contains("Fetched.PAUSED -> pausedNow.add(job).also { loadingHint.imagesPaused() }"))
        // Erst nach «nichts zu laden» (leere Liste) melden: Coins ohne Logo-Adresse lösen nie einen Hinweis aus
        val empty = store.indexOf("if (pending.isEmpty()) return Downloads()")
        assertTrue(empty > 0 && store.indexOf("loadingHint.imagesPending(pending.size, last = lastStage)") > empty)
        val line = File(app, "features/watchlist/WatchlistLogoHint.kt").readText()
        assertTrue(line.contains("val source = LocalCoinLogoSource.current ?: return"))
        assertTrue(line.contains("R.string.watchlist_logos_loading"))
        assertTrue(line.contains("R.string.watchlist_logos_loading_percent, PercentText.unsigned(it.toDouble(), 0)"))
        // Einmal angesagt (ohne Prozent), nicht bei jedem neuen Wert
        assertTrue(line.contains("AnnounceOnce(stringResource(R.string.watchlist_logos_loading))"))
        val body = File(app, "features/watchlist/WatchlistScreenBody.kt").readText()
        assertTrue(body.contains("WatchlistLogoHint(reduceMotion = reduceMotion)"))
    }
}
