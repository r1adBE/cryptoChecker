package com.cryptochecker.app.ui.theme

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.widget.WidgetColors
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * J-14: WCAG-Kontrast der Akzentfarben (primary, auch als Schrift: Dialogknöpfe, Links) auf allen
 * Flächen — hell bis #E2E2E2, dunkel bis #353535 — mindestens 4.5:1; onPrimary auf primary ebenso.
 * Die Werte kommen aus [WidgetColors] (reines Kotlin); ein Quelltext-Abgleich stellt sicher, dass
 * `AppColorSchemes.kt` dieselben primary-Werte hat. Wie iOS `AccentContrastTests`.
 */
class AccentContrastTest {

    private fun ratio(a: Int, b: Int) = WidgetContrast.ratio(a, b)

    @Test
    fun `primary erreicht AA auf allen Flaechen`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val colors = WidgetColors.of(accent, dark)
            for (bg in if (dark) DARK_SURFACES else LIGHT_SURFACES + WARM_SURFACES) {
                val r = ratio(colors.accentColor, bg)
                assertTrue("${accent.name}/dark=$dark on %06X: %.2f".format(bg and 0xFFFFFF, r), r >= 4.5)
            }
        }
    }

    @Test
    fun `onPrimary auf primary erreicht AA`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val colors = WidgetColors.of(accent, dark)
            val r = ratio(colors.onAccentColor, colors.accentColor)
            assertTrue("${accent.name}/dark=$dark: %.2f".format(r), r >= 4.5)
        }
    }

    @Test
    fun `hoher Kontrast erreicht 7 zu 1 auf der dunkelsten Flaeche`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val hc = WidgetContrast.highContrastAccent(WidgetColors.of(accent, dark).accentColor, dark)
            val bg = if (dark) WidgetContrast.DARK_REFERENCE else WidgetContrast.LIGHT_REFERENCE
            assertTrue("${accent.name}/dark=$dark", ratio(hc, bg) >= 7.0)
        }
    }

    @Test
    fun `Werte wie in der Spezifikation`() {
        assertEquals(0xFFA64827.toInt(), WidgetColors.of(AccentColor.ORANGE, false).accentColor)
        assertEquals(0xFFBE2B38.toInt(), WidgetColors.of(AccentColor.RED, false).accentColor)
        assertEquals(0xFF2B5FC8.toInt(), WidgetColors.of(AccentColor.BLUE, false).accentColor)
        assertEquals(0xFF107348.toInt(), WidgetColors.of(AccentColor.GREEN, false).accentColor)
        assertEquals(0xFF22706B.toInt(), WidgetColors.of(AccentColor.MARRS_GREEN, false).accentColor)
        assertEquals(0xFFED835E.toInt(), WidgetColors.of(AccentColor.ORANGE, true).accentColor)
    }

    /** `AccentColor.primary` (Bezug der Kursfarben) = Akzent der Widgets und der App. */
    @Test
    fun `AccentColor primary wie Widget-Akzent`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            assertEquals("${accent.name}/dark=$dark", WidgetColors.of(accent, dark).accentColor, accent.primary(dark))
        }
    }

    /** primary in `AppColorSchemes.kt` = Akzent der Widgets (eine Palette, zwei Stellen). */
    @Test
    fun `App-Farbschema und Widget haben denselben Akzent`() {
        val source = schemesSource() ?: return // Quelle nicht auffindbar (anderes Arbeitsverzeichnis)
        val names = mapOf(
            AccentColor.ORANGE to "orange", AccentColor.RED to "red", AccentColor.BLUE to "blue",
            AccentColor.GREEN to "green", AccentColor.MARRS_GREEN to "marrsGreen",
        )
        for ((accent, name) in names) for (dark in listOf(false, true)) {
            val block = "private val $name${if (dark) "Dark" else "Light"} = "
            val start = source.indexOf(block)
            assertTrue("$block fehlt", start >= 0)
            val match = Regex("""primary = Color\(0x([0-9A-Fa-f]{8})\)""").find(source, start)
            assertTrue("primary in $block fehlt", match != null)
            val primary = match!!.groupValues[1].toLong(16).toInt()
            assertEquals("${accent.name}/dark=$dark", WidgetColors.of(accent, dark).accentColor, primary)
        }
    }

    /** K-9: «warmes Papier» – jede warme Fläche ist mindestens so hell wie der frühere Grauton. */
    @Test
    fun `warme Flaechen sind nicht dunkler als die frueheren Grautoene`() {
        val black = 0xFF000000.toInt()
        for ((old, warm) in WARM_REPLACES) {
            assertTrue(
                "%06X -> %06X".format(old and 0xFFFFFF, warm and 0xFFFFFF),
                ratio(warm, black) >= ratio(old, black) - 1e-9
            )
        }
    }

    /** K-9: Haupt-, Neben- und Warntext, Fehlerrot auf allen warmen Flächen ≥ 4.5:1; Linie ≥ 3:1. */
    @Test
    fun `Text und Linien erreichen den Kontrast auf warmen Flaechen`() {
        for (bg in WARM_SURFACES) {
            for (fg in listOf(0xFF1B1B1B.toInt(), 0xFF474747.toInt(), 0xFF8A5300.toInt(), 0xFFBA1A1A.toInt())) {
                val r = ratio(fg, bg)
                assertTrue("%06X on %06X: %.2f".format(fg and 0xFFFFFF, bg and 0xFFFFFF, r), r >= 4.5)
            }
            assertTrue(ratio(0xFF777777.toInt(), bg) >= 3.0)
        }
    }

    /** K-9: Die hellen Farbschemata in `AppColorSchemes.kt` nutzen die warmen Flächen. */
    @Test
    fun `helle Farbschemata haben die warmen Flaechen`() {
        val source = schemesSource() ?: return
        for (name in listOf("orange", "red", "blue", "green", "marrsGreen")) {
            val start = source.indexOf("private val ${name}Light = ")
            val end = source.indexOf("\n)", start)
            assertTrue("${name}Light fehlt", start >= 0 && end > start)
            val block = source.substring(start, end)
            for ((key, value) in WARM_SCHEME_TOKENS) {
                assertTrue("$name: $key", block.contains("    $key = Color(0xFF$value)"))
            }
        }
    }

    /**
     * Build 46: Bitcoin-Orange als Grafik (Dominanz-Balken) ≥ 3:1 auf allen Flächen seines Modus —
     * hell #F7931A hielt nur ≈ 2.2:1, deshalb dunkler; dunkel bleibt das Original.
     */
    @Test
    fun `Bitcoin-Orange erreicht 3 zu 1 als Grafik in Hell und Dunkel`() {
        for (dark in listOf(false, true)) {
            val btc = AssetPalette.bitcoin(dark)
            for (bg in if (dark) DARK_SURFACES else LIGHT_SURFACES + WARM_SURFACES) {
                val r = ratio(btc, bg)
                assertTrue("dark=$dark btc on %06X: %.2f".format(bg and 0xFFFFFF, r), r >= 3.0)
            }
        }
        // Der frühere Befund: #F7931A auf #FBF9F6 unter 3:1
        assertTrue(ratio(0xFFF7931A.toInt(), 0xFFFBF9F6.toInt()) < 3.0)
        assertEquals(0xFFF7931A.toInt(), AssetPalette.bitcoin(dark = true))
    }

    /** Build 46: Ethereum-Blau (Dominanz-Balken) ≥ 3:1 — hell #627EEA hielt auf #E5E2DB nur ≈ 2.85:1. */
    @Test
    fun `Ethereum-Blau erreicht 3 zu 1 als Grafik in Hell und Dunkel`() {
        for (dark in listOf(false, true)) {
            val eth = AssetPalette.ethereum(dark)
            for (bg in if (dark) DARK_SURFACES else LIGHT_SURFACES + WARM_SURFACES) {
                val r = ratio(eth, bg)
                assertTrue("dark=$dark eth on %06X: %.2f".format(bg and 0xFFFFFF, r), r >= 3.0)
            }
        }
        assertTrue(ratio(0xFF627EEA.toInt(), 0xFFE5E2DB.toInt()) < 3.0)
        assertEquals(0xFF627EEA.toInt(), AssetPalette.ethereum(dark = true))
        assertEquals(0xFF4C64D0.toInt(), AssetPalette.ethereum(dark = false))
    }

    /**
     * Build 46: Rand der Gruppen-Chips (einzige Kontur, WCAG 1.4.11) ist `outline` — ≥ 3:1 auf den
     * Flächen der Kopfzeile; `outlineVariant` (#ADA9A0, ≈ 2.0–2.3:1) bleibt darunter. Werte aus dem Quelltext.
     */
    @Test
    fun `outline als Chip-Rand erreicht 3 zu 1`() {
        val source = schemesSource() ?: return
        for ((outline, surfaces) in listOf(0xFF777777.toInt() to LIGHT_SURFACES + WARM_SURFACES, 0xFF919191.toInt() to DARK_SURFACES)) {
            for (bg in surfaces) {
                val r = ratio(outline, bg)
                assertTrue("outline %06X on %06X: %.2f".format(outline and 0xFFFFFF, bg and 0xFFFFFF, r), r >= 3.0)
            }
        }
        assertTrue(source.contains("outline = Color(0xFF777777)") && source.contains("outline = Color(0xFF919191)"))
        assertTrue(ratio(0xFFADA9A0.toInt(), 0xFFF1EEE9.toInt()) < 3.0)
    }

    /** Build 58: Trennlinien hell ≈ 2.2:1 auf dem Hintergrund #FBF9F6 (vorher #C9C6BF 1.6:1), unter 3:1. */
    @Test
    fun `Trennlinie hell erreicht etwa 2_2 zu 1`() {
        val r = ratio(0xFFADA9A0.toInt(), 0xFFFBF9F6.toInt())
        assertTrue("%.3f".format(r), r in 2.15..2.3)
        assertTrue(ratio(0xFFC9C6BF.toInt(), 0xFFFBF9F6.toInt()) < 1.7)
    }

    /** Build 46: Blau ohne violettes Tertiär; tertiary als Schrift AA, onTertiary auf tertiary AA. */
    @Test
    fun `Blau hat ein blaues Tertiaer mit AA`() {
        val source = schemesSource() ?: return
        assertTrue(!source.contains("0xFF804E73") && !source.contains("0xFFFED7F3"))
        for ((tertiary, on, surfaces) in listOf(
            Triple(0xFF3F5F90.toInt(), 0xFFFFFFFF.toInt(), LIGHT_SURFACES + WARM_SURFACES),
            Triple(0xFFA8C8FF.toInt(), 0xFF05305F.toInt(), DARK_SURFACES),
        )) {
            for (bg in surfaces) assertTrue("tertiary on %06X".format(bg and 0xFFFFFF), ratio(tertiary, bg) >= 4.5)
            assertTrue(ratio(on, tertiary) >= 4.5)
            assertTrue(source.contains("tertiary = Color(0x%08X)".format(tertiary)))
        }
    }

    /** Widget-Nebentext: feste, deckende Farbe (keine Deckkraft) ≥ 4.5:1 auf dem Widget-Grund; wie iOS. */
    @Test
    fun `Widget-Nebentext ist deckend und erreicht AA auf dem Widget-Grund`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val colors = WidgetColors.of(accent, dark)
            val fg = colors.secondaryTextColor
            // Widget-Grund (baseColor, surfaceContainer) — colorWithOpacity braucht android.graphics
            val bg = if (dark) 0xFF1F1F1F.toInt() else 0xFFF1EEE9.toInt()
            assertEquals("${accent.name}/dark=$dark Alpha", 0xFF, (fg ushr 24) and 0xFF)
            assertEquals(if (dark) 0xFFC6C6C6.toInt() else 0xFF474747.toInt(), fg)
            val r = ratio(fg, bg)
            assertTrue("${accent.name}/dark=$dark: %.2f".format(r), r >= 4.5)
        }
    }

    /**
     * Eingabefelder (Suche der Merkliste, Gruppenname) haben einen Kontroll-Rand (`outline`,
     * WCAG 1.4.11 ≥ 3:1 auf allen Flächen), keinen Trennlinien-Ton (`outlineVariant`).
     */
    @Test
    fun `Rand der Eingabefelder erreicht 3 zu 1`() {
        for (bg in LIGHT_SURFACES + WARM_SURFACES) {
            val r = ratio(0xFF777777.toInt(), bg)
            assertTrue("light outline on %06X: %.2f".format(bg and 0xFFFFFF, r), r >= 3.0)
        }
        for (bg in DARK_SURFACES) {
            val r = ratio(0xFF919191.toInt(), bg)
            assertTrue("dark outline on %06X: %.2f".format(bg and 0xFFFFFF, r), r >= 3.0)
        }
        val source = schemesSource() ?: return
        assertTrue(source.contains("outline = Color(0xFF777777)") && source.contains("outline = Color(0xFF919191)"))
        for (file in listOf("watchlist/WatchlistSearch.kt", "watchlist/WatchGroupEditSheet.kt")) {
            val text = sourceFile("src/main/java/com/cryptochecker/app/ui/features/$file") ?: continue
            assertTrue("$file: Feldrand", text.contains("BorderStroke(1.dp, MaterialTheme.colorScheme.outline)"))
            assertTrue("$file: Trennlinien-Ton als Feldrand", !text.contains("outlineVariant.copy(alpha = 0.6f)"))
        }
    }

    private fun schemesSource(): String? = sourceFile("src/main/java/com/cryptochecker/app/ui/theme/AppColorSchemes.kt")

    private fun sourceFile(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, prefix + rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        /** surfaceContainerLowest/surface … surfaceContainerHighest, tiefe Widget-Fläche #E4E4E4. */
        val LIGHT_SURFACES = listOf(
            0xFFFFFFFF.toInt(), 0xFFF9F9F9.toInt(), 0xFFF3F3F3.toInt(), 0xFFEEEEEE.toInt(),
            0xFFE8E8E8.toInt(), 0xFFE4E4E4.toInt(), 0xFFE2E2E2.toInt(),
        )
        /** K-9 warm: Lowest, surface, Low, Container (Widget), High, tiefe Widget-Fläche, Highest. */
        val WARM_SURFACES = listOf(
            0xFFFFFDFA.toInt(), 0xFFFBF9F6.toInt(), 0xFFF6F3EE.toInt(), 0xFFF1EEE9.toInt(),
            0xFFEBE8E2.toInt(), 0xFFE7E4DD.toInt(), 0xFFE5E2DB.toInt(),
        )
        /** Früherer Grauton → warmer Ersatz. */
        val WARM_REPLACES = listOf(
            0xFFF9F9F9.toInt() to 0xFFFBF9F6.toInt(), 0xFFF3F3F3.toInt() to 0xFFF6F3EE.toInt(),
            0xFFEEEEEE.toInt() to 0xFFF1EEE9.toInt(), 0xFFE8E8E8.toInt() to 0xFFEBE8E2.toInt(),
            0xFFE4E4E4.toInt() to 0xFFE7E4DD.toInt(), 0xFFE2E2E2.toInt() to 0xFFE5E2DB.toInt(),
            0xFFDADADA.toInt() to 0xFFDDDAD3.toInt(), 0xFFF1F1F1.toInt() to 0xFFF3F1EB.toInt(),
            // outlineVariant bewusst dunkler (Build 58, ≈ 2.2:1): eigener Test
        )
        val WARM_SCHEME_TOKENS = listOf(
            "background" to "FBF9F6", "surface" to "FBF9F6", "surfaceBright" to "FBF9F6",
            "surfaceVariant" to "E5E2DB", "outlineVariant" to "ADA9A0", "surfaceDim" to "DDDAD3",
            "surfaceContainerLowest" to "FFFDFA", "surfaceContainerLow" to "F6F3EE",
            "surfaceContainer" to "F1EEE9", "surfaceContainerHigh" to "EBE8E2",
            "surfaceContainerHighest" to "E5E2DB", "inverseOnSurface" to "F3F1EB",
        )
        /** surfaceContainerLowest … surfaceContainerHighest, Auswahlfläche #333333. */
        val DARK_SURFACES = listOf(
            0xFF0E0E0E.toInt(), 0xFF121212.toInt(), 0xFF131313.toInt(), 0xFF1B1B1B.toInt(),
            0xFF1F1F1F.toInt(), 0xFF2A2A2A.toInt(), 0xFF333333.toInt(), 0xFF353535.toInt(),
        )
    }
}
