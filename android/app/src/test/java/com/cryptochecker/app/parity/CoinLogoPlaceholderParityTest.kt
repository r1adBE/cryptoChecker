package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogoPlaceholder
import com.cryptochecker.app.domain.widget.WidgetContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ersatz-Kreis ohne Logo ([CoinLogoPlaceholder]), gemeinsame Testfälle `coin_placeholder.json` (iOS:
 * `CoinLogoPlaceholderTests.swift`): dieselbe Palette, dieselbe Farbe je Kürzel und Schrift mit WCAG AA auf jeder
 * Füllung, hell und dunkel.
 */
class CoinLogoPlaceholderParityTest {

    private val data = ParityJson.load("coin_placeholder.json")

    private fun colors(key: String): List<Int> = data[key].list().map { (0xFF000000 or (it as String).toLong(16)).toInt() }

    private fun color(key: String): Int = (0xFF000000 or (data[key] as String).toLong(16)).toInt()

    @Test
    fun paletteAsFixture() {
        assertEquals(colors("fillsLight"), CoinLogoPlaceholder.FILLS_LIGHT.toList())
        assertEquals(colors("fillsDark"), CoinLogoPlaceholder.FILLS_DARK.toList())
        assertEquals(color("textLight"), CoinLogoPlaceholder.TEXT_LIGHT)
        assertEquals(color("textDark"), CoinLogoPlaceholder.TEXT_DARK)
        assertEquals((data["fadeMillis"] as Double).toInt(), CoinLogoPlaceholder.FADE_MILLIS)
    }

    @Test
    fun initialsReachAAOnEveryFill() {
        val min = data["minContrast"] as Double
        for (dark in listOf(false, true)) {
            val fills = if (dark) CoinLogoPlaceholder.FILLS_DARK else CoinLogoPlaceholder.FILLS_LIGHT
            for (fill in fills) {
                val r = WidgetContrast.ratio(CoinLogoPlaceholder.text(dark), fill)
                assertTrue("dark=$dark %06X: %.2f".format(fill and 0xFFFFFF, r), r >= min)
            }
        }
    }

    @Test
    fun colourFollowsTheSymbol() {
        data["cases"].list().map { it.obj() }.forEach { c ->
            val symbol = c["symbol"] as String
            assertEquals("key $symbol", c["key"], CoinLogoPlaceholder.key(symbol))
            val index = (c["index"] as Double).toInt()
            assertEquals("index $symbol", index, CoinLogoPlaceholder.index(symbol))
            assertEquals(CoinLogoPlaceholder.FILLS_DARK[index], CoinLogoPlaceholder.fill(symbol, dark = true))
            assertEquals(CoinLogoPlaceholder.FILLS_LIGHT[index], CoinLogoPlaceholder.fill(symbol, dark = false))
        }
    }

    @Test
    fun differentSymbolsSpreadOverThePalette() {
        val used = listOf("BTC", "ETH", "SOL", "XRP", "DOGE", "ADA", "BNB", "TRX", "LINK", "AVAX", "DOT", "SUI")
            .map { CoinLogoPlaceholder.index(it) }.toSet()
        assertTrue("Farben: $used", used.size >= 5)
    }
}
