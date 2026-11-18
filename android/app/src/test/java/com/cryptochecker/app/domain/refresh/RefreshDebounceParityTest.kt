package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 15-s-Sperre für «Aktualisieren» liest nur das Ende der letzten VOLLSTÄNDIGEN Aktualisierung
 * (`RefreshStats.lastFullRefresh`), nicht die Uhrzeit der Live-Takte (`lastRefresh`) — `refresh_debounce.json`,
 * geteilt mit iOS (`RefreshDebounceParityTests`). Die Aufrufer prüft die Regel `android-wiring` in
 * `.github/scripts/source_rules.py`.
 */
class RefreshDebounceParityTest {

    private val data = ParityJson.load("refresh_debounce.json")

    @Test
    fun parityCases() {
        val now = data["now"].num()!!.toLong()
        val cases = data["cases"].list()
        assertTrue(cases.size >= 5)
        cases.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val full = c["fullAgo"].num()?.let { now - it.toLong() } ?: 0L
            val decision = RefreshDebounce.decide(c["running"] as Boolean, full, now)
            assertEquals(name, c["expected"], decision.name.lowercase())
            assertEquals(name, c["remainingSeconds"].num()!!.toInt(), RefreshDebounce.remainingSeconds(full, now))
        }
    }
}
