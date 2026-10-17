package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.util.DecimalText
import com.cryptochecker.app.util.WidgetQuantityFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemeinsame Testfälle `widget_quantity.json` (iOS: `WidgetQuantityFormatTests.swift`). */
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
        // Ohne Mengen (ältere Aufrufe): keine
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
