package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.util.DecimalText
import com.cryptochecker.app.util.WidgetQuantityFormat
import com.cryptochecker.app.widget.PortfolioChartRange
import com.cryptochecker.app.widget.PortfolioDisplay
import com.cryptochecker.app.widget.PortfolioWidgetOptions
import com.cryptochecker.app.widget.PortfolioWidgetView
import com.cryptochecker.app.widget.WidgetListRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus WidgetListRowsParityTest

/**
 * Zeilen des Merklisten-Widgets bei grosser Schrift als gemeinsame Testfälle (`testdata/parity/widget_list_rows.json`,
 * Kopie in iOS `Tests/Parity/`, gelesen von `WidgetListRowsParityTests`).
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

// ---- aus WidgetPortfolioOptionsParityTest

/** Gemeinsame Testfälle `widget_portfolio_options.json` (iOS: `PortfolioWidgetOptionsTests`). */
class WidgetPortfolioOptionsParityTest {

    private val data = ParityJson.load("widget_portfolio_options.json")

    /** iOS-Rohwert («conversionUsdt») → Kotlin-Enum-Name («CONVERSION_USDT»). */
    private fun enumName(raw: Any?): String = (raw as String).replace(Regex("([A-Z])"), "_$1").uppercase()

    private fun view(raw: Any?) = PortfolioWidgetView.valueOf(enumName(raw))
    private fun display(raw: Any?) = PortfolioDisplay.valueOf(enumName(raw))
    private fun range(raw: Any?) = PortfolioChartRange.valueOf(enumName(raw))

    @Test
    fun offered() {
        val offered = data["offered"].obj()
        assertEquals(PortfolioWidgetView.entries.size, offered.size)
        offered.forEach { (name, raw) ->
            val v = view(name)
            val o = raw.obj()
            assertEquals("displays $name", o["displays"].list().map { display(it) }, PortfolioWidgetOptions.displays(v))
            assertEquals("ranges $name", o["ranges"].list().map { range(it) }, PortfolioWidgetOptions.ranges(v))
        }
    }

    @Test
    fun cases() {
        val cases = data["cases"].list()
        // Alle Kombinationen Ansicht × Anzeige × Zeitraum
        assertEquals(
            PortfolioWidgetView.entries.size * PortfolioDisplay.entries.size * PortfolioChartRange.entries.size,
            cases.size,
        )
        cases.forEach { raw ->
            val c = raw.obj()
            val v = view(c["view"])
            val label = "${c["view"]} ${c["display"]} ${c["range"]}"
            assertEquals("display $label", display(c["effectiveDisplay"]),
                PortfolioWidgetOptions.effectiveDisplay(v, display(c["display"])))
            assertEquals("range $label", range(c["effectiveRange"]),
                PortfolioWidgetOptions.effectiveRange(v, range(c["range"])))
        }
    }

    @Test
    fun effectiveIsAlwaysOffered() {
        // Was wirkt, ist in der Einrichtung auch wählbar (sonst wäre keine Wahl markiert)
        PortfolioWidgetView.entries.forEach { v ->
            PortfolioDisplay.entries.forEach { d ->
                assertTrue("$v $d", PortfolioWidgetOptions.effectiveDisplay(v, d) in PortfolioWidgetOptions.displays(v))
            }
            if (v.usesRange) {
                PortfolioChartRange.entries.forEach { r ->
                    assertTrue("$v $r", PortfolioWidgetOptions.effectiveRange(v, r) in PortfolioWidgetOptions.ranges(v))
                }
            }
        }
    }
}

// ---- aus WidgetQuantityParityTest

/** Gemeinsame Testfälle `widget_quantity.json` (iOS: `WidgetQuantityFormatTests`). */
class WidgetQuantityParityTest {

    private val data = ParityJson.load("widget_quantity.json")

    @Test
    fun cases() {
        val cases = data["cases"].list()
        assertTrue(cases.size >= 30)
        cases.forEach { raw ->
            val c = raw.obj()
            val actual = WidgetQuantityFormat.format(c["text"] as String?, c["decimal"] as String, c["grouping"] as String)
            assertEquals("format '${c["text"]}' (${c["decimal"]} ${c["grouping"]})", c["expected"], actual)
        }
    }

    @Test
    fun fromDouble() {
        data["fromDouble"].list().forEach { raw ->
            val c = raw.obj()
            val text = DecimalText.plain((c["value"] as String).toDouble())
            assertEquals("fromDouble ${c["value"]}", c["expected"], WidgetQuantityFormat.format(text, ".", ","))
        }
    }

    @Test
    fun hiddenAndSpoken() {
        assertNull(WidgetQuantityFormat.visible("1.3", hidden = true))
        assertNull(WidgetQuantityFormat.visible(null, hidden = false))
        assertEquals("1.3 BTC", WidgetQuantityFormat.spokenName("BTC", "1.3"))
        assertEquals("BTC", WidgetQuantityFormat.spokenName("BTC", null))
    }

    @Test
    fun snapshotCarriesQuantity() {
        // Menge je Position aus dem Bestand (inkl. beobachteter Adressen), als Dezimaltext
        val top = PortfolioWidgetMath.topPositions(
            valuesUsd = mapOf("BTC" to 60_000.0, "ETH" to 3_000.0, "XRP" to null),
            totalUsd = 63_000.0,
            fxRate = 1.0,
            changes = emptyMap(),
            amounts = mapOf("BTC" to 1.13, "ETH" to 1.2e-5),
        )
        assertEquals(listOf("1.13", "0.000012"), top.positions.map { it.quantity })
        // Ohne Mengen: keine
        val old = PortfolioWidgetMath.topPositions(mapOf("BTC" to 1.0), 1.0, 1.0, emptyMap())
        assertNull(old.positions.single().quantity)

        val snapshot = PortfolioSnapshotMath.snapshot(
            holdings = mapOf("BTC" to 0.5, "ETH" to 12.4567),
            totalUsd = 0.5 * 60_000.0 + 12.4567 * 3_000.0,
            current = mapOf("BTC" to 60_000.0, "ETH" to 3_000.0),
            history = emptyList(),
            now = 1_700_000_000_000L,
            fxRate = 1.0,
            currency = "USD",
        )
        val shown = snapshot.positions.associate { it.symbol to WidgetQuantityFormat.format(it.quantity, ".", ",") }
        assertEquals(mapOf("ETH" to "12.45", "BTC" to "0.5"), shown)
    }
}
