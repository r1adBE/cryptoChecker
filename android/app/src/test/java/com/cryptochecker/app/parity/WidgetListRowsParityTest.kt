package com.cryptochecker.app.parity

import com.cryptochecker.app.widget.WidgetListRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zeilen des Merklisten-Widgets bei grosser Schrift als gemeinsame Testfälle (`testdata/parity/widget_list_rows.json`,
 * Kopie in iOS `Tests/Parity/`, gelesen von `WidgetListRowsParityTests.swift`).
 */
class WidgetListRowsParityTest {

    private val data = ParityJson.load("widget_list_rows.json")

    @Test
    fun rowsFor() {
        val cases = data["rowsFor"].list()
        assertTrue(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val rows = WidgetListRows.rowsFor(
                height = c["height"].num()!!,
                scale = c["scale"].num()!!,
                baseRows = c["baseRows"].num()!!.toInt(),
            )
            assertEquals(c["name"] as String, c["rows"].num()!!.toInt(), rows)
        }
    }

    @Test
    fun fewerRowsOnlyWithLargerText() {
        assertEquals(4, WidgetListRows.rowsFor(112.0, 1.0, 4))
        assertEquals(10, WidgetListRows.rowsFor(308.0, 0.8, 10))
        assertTrue(WidgetListRows.rowsFor(308.0, 1.3, 10) < 10)
        assertEquals(WidgetListRows.rowsFor(308.0, 1.3, 10), WidgetListRows.rowsFor(308.0, 3.0, 10))
    }
}
