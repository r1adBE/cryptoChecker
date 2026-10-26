package com.cryptochecker.app.parity

import com.cryptochecker.app.data.remote.MirrorLoader
import com.cryptochecker.app.data.remote.MirrorTrust
import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.mirror.MirrorManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Wirtschaftskalender nur mit SHA-256 im signierten Spiegel-Manifest (`macro_calendar_signature.json`, gleich in
 * iOS `MacroCalendarSignatureTests.swift`): unveränderte Datei → Termine; veränderte oder nicht gelistete Datei
 * → null, dann nimmt `MacroCalendarRepository` den Zwischenspeicher oder die mitgelieferte Datei.
 */
class MacroCalendarSignatureParityTest {

    private val data = ParityJson.load("macro_calendar_signature.json")
    private val testKey = data["publicKey"] as String
    private val now = (data["now"] as Number).toLong()

    @Suppress("UNCHECKED_CAST")
    private val manifests = data["manifests"] as Map<String, Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private val cases = (data["cases"] as List<Any?>).map { it as Map<String, Any?> }

    private fun loader(which: String): MirrorLoader = { url, _ ->
        when (url) {
            MirrorManifest.URL -> manifests.getValue(which)["manifest"] as String
            MirrorManifest.SIGNATURE_URL -> manifests.getValue(which)["signature"] as String
            else -> null
        }
    }

    @Test
    fun constantsMatchFixture() {
        assertEquals(data["manifestFile"], MacroCalendar.MANIFEST_FILE)
        assertEquals(data["url"], MacroCalendar.URL)
    }

    @Test
    fun onlyListedUnchangedFileIsAccepted() = runBlocking {
        for (case in cases) {
            val name = case["name"] as String
            val trust = MirrorTrust(testKey) { now }
            val load = loader(case["manifest"] as String)
            val events = MacroCalendar.parseVerified(case["text"] as String, now) { file, text ->
                trust.verified(file, text, load)
            }
            if (case["accepted"] == true) {
                assertNotNull(name, events)
                assertEquals(name, (case["events"] as Number).toInt(), events!!.size)
            } else {
                assertNull(name, events)
            }
        }
    }

    @Test
    fun productionKeyRejectsTestManifest() = runBlocking {
        val accepted = cases.first { it["accepted"] == true }
        val trust = MirrorTrust(MirrorManifest.PUBLIC_KEYS, { now })
        val events = MacroCalendar.parseVerified(accepted["text"] as String, now) { file, text ->
            trust.verified(file, text, loader("with"))
        }
        assertNull(events)
    }

    @Test
    fun manipulatedFileFallsBackToBundledCalendar() = runBlocking {
        val manipulated = cases.first { it["name"] == "event time changed" }
        val trust = MirrorTrust(testKey) { now }
        val events = MacroCalendar.parseVerified(manipulated["text"] as String, now) { file, text ->
            trust.verified(file, text, loader("with"))
        }
        assertNull(events)
        // Rückfall: die mitgelieferte Datei ist ein gültiger Kalender
        val bundled = File(ParityJson.dir().parentFile!!.parentFile, "app/src/main/assets/macro_events.json")
        assertNotNull(MacroCalendar.parse(bundled.readText(Charsets.UTF_8), now))
        assertFalse(MirrorTrust(testKey) { now }.verified(MacroCalendar.MANIFEST_FILE, "{}", loader("with")))
    }
}
