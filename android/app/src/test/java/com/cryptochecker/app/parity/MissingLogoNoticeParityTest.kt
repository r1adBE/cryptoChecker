package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.LogoLoadingHint
import com.cryptochecker.app.domain.logos.MissingLogoNotice
import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Hinweis «2 Coins ohne Logo» ([MissingLogoNotice]): gemeinsame Testfälle `missing_logos.json` (iOS:
 * `MissingLogoNoticeTests.swift`), «erstes Laden erledigt» aus [LogoLoadingHint] und die Anbindung im Quelltext —
 * an der Stelle des Lade-Hinweises, nur mit Logo-Quelle (Schalter aus → nie), ✕ mit 48 dp und eigener Bedienhilfe,
 * gemerkter Satz in der nicht gesicherten Einstellungsdatei der Logos.
 */
class MissingLogoNoticeParityTest {

    private val fixture = ParityJson.load("missing_logos.json")

    @Test
    fun candidatesAsFixture() {
        val cases = fixture["candidates"].list().map { it.obj() }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val pairs = case["pairs"].list().map { p ->
                val row = p.list()
                MissingLogoNotice.WatchedCoin(row[0] as String, row[1] as String, tradFi = row[2] as Boolean)
            }
            assertEquals(case["name"] as String, case["keys"].list(), MissingLogoNotice.candidates(pairs))
        }
    }

    @Test
    fun stateAsFixture() {
        val cases = fixture["state"].list().map { it.obj() }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val got = MissingLogoNotice.state(
                missing = case["missing"].list().map { it as String },
                dismissed = case["dismissed"].list().map { it as String }.toSet(),
                loading = case["loading"] as Boolean,
                logosEnabled = case["enabled"] as Boolean,
            )
            assertEquals("${case["name"]} › visible", case["visible"] as Boolean, got.visible)
            assertEquals("${case["name"]} › count", case["count"].num()!!.toInt(), got.count)
        }
    }

    @Test
    fun storedSetAsFixture() {
        for (case in fixture["stored"].list().map { it.obj() }) {
            val keys = case["keys"].list().map { it as String }.toSet()
            assertEquals(case["text"] as String, MissingLogoNotice.encode(keys))
            assertEquals(keys, MissingLogoNotice.decode(case["text"] as String))
        }
        assertEquals(emptySet<String>(), MissingLogoNotice.decode(null))
    }

    /** Erstes Laden erledigt: gleich nach dem Ende des Durchgangs (vor der Merk-Datei), danach aus der Merk-Datei. */
    @Test
    fun firstLoadDoneFromRunAndMarker() {
        val dir = Files.createTempDirectory("logos").toFile()
        try {
            val marker = File(dir, LogoLoadingHint.DONE_MARKER)
            val hint = LogoLoadingHint(doneMarker = { marker })
            assertFalse(hint.firstLoadDone())
            hint.runStarted(firstLoadDone = false)
            hint.imagesPending(3)
            assertFalse(hint.firstLoadDone())
            assertTrue(hint.runFinished())
            assertTrue(hint.firstLoadDone())
            // Neuer Prozess: nur die Merk-Datei zählt
            val later = LogoLoadingHint(doneMarker = { marker })
            assertFalse(later.firstLoadDone())
            marker.createNewFile()
            assertTrue(later.firstLoadDone())
            // Zwischenspeicher geleert (Merk-Datei weg, neues erstes Laden): wieder offen
            marker.delete()
            later.runStarted(firstLoadDone = false)
            assertFalse(later.firstLoadDone())
            // Durchgang ohne Bilder (z. B. ohne Netz): erstes Laden bleibt offen
            later.runFinished()
            assertFalse(later.firstLoadDone())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wiredAtTheLoadingHintWithTheLogoSwitch() {
        val app = SourceGuard.android(
            listOf("src/main/java/com/cryptochecker/app/ui", "app/src/main/java/com/cryptochecker/app/ui")
                .map(::File).firstOrNull { it.isDirectory },
            "app/src/main/java/com/cryptochecker/app/ui",
        )
        val data = SourceGuard.android(
            listOf("../data/src/main/java/com/cryptochecker/app/data", "data/src/main/java/com/cryptochecker/app/data")
                .map(::File).firstOrNull { it.isDirectory },
            "data/src/main/java/com/cryptochecker/app/data",
        )
        val line = File(app, "features/watchlist/WatchlistMissingLogos.kt").readText()
        assertTrue(line.contains("val source = LocalCoinLogoSource.current ?: return"))
        // Nie während des ersten Ladens bzw. solange «Logos werden geladen» steht
        assertTrue(line.contains("if (loadingLine || !source.firstLoadDone()) null else keys.filterNot { source.hasLogo(it) }"))
        assertTrue(line.contains("MissingLogoNotice.state(current, stored, loading = loadingLine, logosEnabled = true)"))
        assertTrue(line.contains("R.plurals.watchlist_missing_logos, count, count"))
        assertTrue(line.contains("contentDescription = stringResource(R.string.watchlist_missing_logos_dismiss)"))
        assertTrue(line.contains("IconButton(onClick = { source.dismissMissing(shown[0].toSet()) }, modifier = Modifier.size(TOUCH_MIN))"))
        assertTrue(line.contains("private val TOUCH_MIN = 48.dp"))
        assertTrue(line.contains("R.string.watchlist_missing_logos_explain"))
        val body = File(app, "features/watchlist/WatchlistScreenBody.kt").readText()
        val hint = body.indexOf("WatchlistLogoHint(reduceMotion = reduceMotion)")
        val notice = body.indexOf("WatchlistMissingLogos(watches, reduceMotion = reduceMotion)")
        assertTrue(hint > 0 && notice > hint)
        // Gemerkter Satz: Einstellungsdatei der Logos (nicht in backup_rules/data_extraction_rules)
        val store = File(data, "MissingLogoDismissals.kt").readText()
        assertTrue(store.contains("private val prefs by lazy { CoinLogoStorage.prefs(context) }"))
    }
}
