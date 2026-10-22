package com.cryptochecker.app.parity

import com.cryptochecker.app.widget.PortfolioChartRange
import com.cryptochecker.app.widget.PortfolioDisplay
import com.cryptochecker.app.widget.PortfolioWidgetOptions
import com.cryptochecker.app.widget.PortfolioWidgetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `widget_portfolio_options.json` (iOS: `PortfolioWidgetOptionsTests.swift`). */
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
