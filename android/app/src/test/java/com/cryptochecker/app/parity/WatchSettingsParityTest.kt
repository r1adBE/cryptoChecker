package com.cryptochecker.app.parity

import com.cryptochecker.marketdata.watch.WatchCoins
import com.cryptochecker.marketdata.watch.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Uhr-Apps (`watch_settings.json`) — wie `WatchSettingsParityTests.swift`: Listen, JSON und Zusammenführen nach
 * Änderungsnummer (Gleichstand: Handy).
 */
class WatchSettingsParityTest {

    private val data = ParityJson.load("watch_settings.json")

    @Suppress("UNCHECKED_CAST")
    private fun strings(v: Any?): List<String> = v as List<String>

    private fun settings(v: Any?): WatchSettings {
        val o = v.obj()
        return WatchSettings(
            mode = WatchSettings.Mode.entries.first { it.key == o["mode"] },
            own = strings(o["own"]),
            phone = strings(o["phone"]),
            revision = o["revision"].num()!!.toLong(),
        )
    }

    @Test
    fun coins() {
        assertEquals(strings(data["top"]), WatchCoins.TOP)
        for (c in data.getValue("toggle").list().map { it.obj() }) {
            assertEquals(strings(c["expected"]), WatchCoins.toggle(strings(c["list"]), c["coin"] as String))
        }
        for (c in data.getValue("cleanOwn").list().map { it.obj() }) {
            assertEquals(strings(c["expected"]), WatchCoins.cleanOwn(strings(c["stored"])))
        }
        for (c in data.getValue("fromFavorites").list().map { it.obj() }) {
            assertEquals(strings(c["expected"]), WatchCoins.fromFavorites(strings(c["bases"])))
        }
    }

    @Test
    fun json() {
        for (c in data.getValue("fromJson").list().map { it.obj() }) {
            val parsed = WatchSettings.fromJson(c["text"] as String)
            val e = c["expected"]
            if (e == null) {
                assertNull(parsed)
                continue
            }
            val expected = settings(e)
            assertEquals(expected, parsed)
            assertEquals(strings(e.obj()["shown"]), parsed!!.shown)
            assertEquals(parsed, WatchSettings.fromJson(parsed.toJson()))
        }
    }

    @Test
    fun merge() {
        for (c in data.getValue("merge").list().map { it.obj() }) {
            val merged = WatchSettings.merge(settings(c["local"]), settings(c["remote"]), c["remoteIsPhone"] as Boolean)
            assertEquals(c["name"] as String, settings(c["expected"]), merged)
        }
    }

    @Test
    fun nextRevision() {
        for (c in data.getValue("nextRevision").list().map { it.obj() }) {
            val before = WatchSettings(revision = c["revision"].num()!!.toLong())
            assertEquals(c["expected"].num()!!.toLong(), before.nextRevision().revision)
            assertEquals(before.copy(revision = 0), before.nextRevision().copy(revision = 0))
        }
    }
}
