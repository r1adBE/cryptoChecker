package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddButtonScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schwebender «+»-Knopf des Portfolios beim Scrollen (`testdata/parity/add_button_scroll.json`): nach unten weg, nach
 * oben wieder da, am Anfang und am Ende immer sichtbar. Gegenstück: `Tests/AddButtonScrollParityTests.swift`.
 */
class AddButtonScrollParityTest {

    private val fixture = ParityJson.load("add_button_scroll.json")

    @Test
    fun travelMatches() {
        assertEquals(fixture["travel"].num()!!, AddButtonScroll.TRAVEL, 0.0)
    }

    @Test
    fun stepsMatchSharedCases() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 10)
        for (raw in cases) {
            val c = raw.obj()
            var state = AddButtonScroll.State()
            c["steps"].list().forEachIndexed { index, rawStep ->
                val step = rawStep.obj()
                state = AddButtonScroll.next(
                    state,
                    delta = step["delta"].num()!!,
                    atTop = step["atTop"] as Boolean,
                    atEnd = step["atEnd"] as Boolean,
                )
                assertEquals("${c["name"]} · Schritt ${index + 1}", step["visible"] as Boolean, state.visible)
            }
        }
    }
}
