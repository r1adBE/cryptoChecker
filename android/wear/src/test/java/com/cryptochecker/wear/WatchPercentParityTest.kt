package com.cryptochecker.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Prozent auf der Uhr wie in der Phone-App (`testdata/parity/percent_text.json`): dieselben Fälle wie `PercentText`
 * und die Änderung der Uhr samt Richtungs-Insel in RTL-Sprachen (`watch_change`). Gegenstück: iOS
 * `WatchPercentParityTests.swift`.
 */
class WatchPercentParityTest {

    private val data = JSONObject(fixtureDir().resolve("percent_text.json").readText(Charsets.UTF_8))

    /** Ordner `testdata/parity`: Gradle startet Unit-Tests im Modulordner (`wear/`), andere Läufer im Projektordner. */
    private fun fixtureDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/parity")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("testdata/parity not found above ${System.getProperty("user.dir")}")
    }

    private fun locale(id: String): Locale = Locale.forLanguageTag(id.replace('_', '-'))

    @Test
    fun prozentWieDiePhoneApp() {
        val cases = data.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val value = c.getDouble("value")
            val decimals = c.getInt("decimals")
            val actual = if (c.getString("kind") == "signed") {
                WatchFormat.signedPercent(value, decimals, locale(c.getString("locale")))
            } else {
                WatchFormat.unsignedPercent(value, decimals, locale(c.getString("locale")))
            }
            assertEquals("${c.getString("locale")} $value", c.getString("expected"), actual)
        }
    }

    @Test
    fun aenderungDerUhr() {
        val cases = data.getJSONObject("watch_change").getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val value = if (c.isNull("value")) null else c.getDouble("value")
            assertEquals("${c.getString("locale")} $value", c.getString("expected"),
                WatchFormat.change(value, locale(c.getString("locale"))))
        }
    }
}
