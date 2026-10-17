package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.mirror.DataMirror
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gemeinsame Testfälle `data_mirror.json` (iOS: `DataMirrorTests.swift`). */
class DataMirrorParityTest {

    private val data = ParityJson.load("data_mirror.json")

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = (data[key] as List<Any?>).map { it as Map<String, Any?> }

    @Test
    fun entries() {
        assertEquals(data["base"], DataMirror.BASE)
        list("entries").forEach { c ->
            val entry = DataMirror.entryFor(c["url"] as String)
            assertEquals("file: $c", c["file"], entry?.file)
            if (entry != null) {
                assertEquals("maxAge: $c", (c["maxAgeMillis"] as Number).toLong(), entry.maxAgeMillis)
                assertEquals(DataMirror.BASE + entry.file, entry.url)
            }
        }
    }

    @Test
    fun unwrap() {
        val now = (data["now"] as Number).toLong()
        list("unwrap").forEach { c ->
            assertEquals("unwrap: $c", c["expected"], DataMirror.unwrap(c["text"] as String?, now, (c["maxAgeMillis"] as Number).toLong()))
        }
    }

    @Test
    fun altSeason() {
        @Suppress("UNCHECKED_CAST")
        val entry = data["altSeasonEntry"] as Map<String, Any?>
        assertEquals(entry["file"], DataMirror.ALT_SEASON.file)
        assertEquals((entry["maxAgeMillis"] as Number).toLong(), DataMirror.ALT_SEASON.maxAgeMillis)
        list("altSeason").forEach { c ->
            @Suppress("UNCHECKED_CAST")
            val e = c["expected"] as Map<String, Any?>?
            val expected = e?.let {
                DataMirror.AltSeasonValue((it["outperformers"] as Number).toInt(), (it["total"] as Number).toInt(), it["provider"] as String?)
            }
            assertEquals("altSeason: $c", expected, DataMirror.parseAltSeason(c["body"] as String?))
        }
    }
}
