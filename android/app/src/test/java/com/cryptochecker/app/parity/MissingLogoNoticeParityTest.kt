package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.LogoLoadingHint
import com.cryptochecker.app.domain.logos.MissingLogoNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Hinweis «2 Coins ohne Logo» ([MissingLogoNotice]): gemeinsame Testfälle `missing_logos.json` (iOS:
 * `MissingLogoNoticeTests.swift`), «erstes Laden erledigt» aus [LogoLoadingHint] (Anbindung: Gerätetest
 * `MissingLogosHintTest`, Regel missing-logos).
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

    // Anbindung (Zeile unter dem Lade-Hinweis, ✕, Schalter, Ablage): Gerätetest MissingLogosHintTest und Regel
    // missing-logos in .github/scripts/source_rules.py (Audit A-01)
}
