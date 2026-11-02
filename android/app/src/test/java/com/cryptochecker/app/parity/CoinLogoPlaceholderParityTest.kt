package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogoPlaceholder
import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * Quelltext-Wächter: App-Plakette und Widgets beider Plattformen zeichnen den Ersatz-Kreis aus [CoinLogoPlaceholder]
     * (nicht mehr die getönte Akzentfarbe); das Einblenden beachtet «Animationen entfernen» bzw. «Bewegung reduzieren».
     */
    @Test
    fun badgesAndWidgetsUseThePlaceholder() {
        val badge = source("ui/components/CoinBadge.kt")
        assertTrue(badge.contains("CoinLogoPlaceholder.fill(coin, dark)"))
        assertTrue(badge.contains("rememberReduceMotion()") && badge.contains("shown.snapTo(1f)"))
        assertFalse(badge.contains("INITIALS_TINT"))
        assertTrue(source("widget/WidgetCoinLogos.kt").contains("colors.placeholderFill(symbol)"))
        val iosBadge = iosSource("App/Components/CoinBadge.swift")
        assertTrue(iosBadge.contains("CoinLogoPlaceholder.fill(symbol, dark: dark)"))
        assertTrue(iosBadge.contains("withAnimation(reduceMotion ? nil"))
        assertTrue(iosSource("Widgets/WidgetBadges.swift").contains("CoinLogoPlaceholder.fill(symbol, dark: dark)"))
    }

    private fun source(rel: String): String {
        val file = findUp(listOf("", "app/", "android/app/").map { "${it}src/main/java/com/cryptochecker/app/$rel" })
        return SourceGuard.android(file, rel).readText(Charsets.UTF_8)
    }

    private fun iosSource(rel: String): String {
        val file = findUp(listOf("ios/$rel", "../ios/$rel", "../../ios/$rel"))
        return SourceGuard.ios(file, "ios/$rel").readText(Charsets.UTF_8)
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
