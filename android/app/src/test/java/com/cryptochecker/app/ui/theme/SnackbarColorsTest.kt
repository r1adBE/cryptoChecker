package com.cryptochecker.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Meldungen unten ([SnackbarPalette], `AppSnackbarHost`): Art → Farbe und Kontrast auf der Fläche
 * `surfaceContainerHighest`, gelesen aus den echten Schemata [appColorScheme]. Wie iOS `SnackbarColorsTests`.
 */
class SnackbarColorsTest {

    private fun ratio(a: Int, b: Int) = WidgetContrast.ratio(a, b)

    private val accents: List<AccentColor?> = AccentColor.entries + null

    /** Erfolg ist keine Kursrichtung: bei «Farben tauschen» (Rot = steigend) bleibt er die Steigend-Farbe. */
    @Test
    fun `Erfolg bleibt die Steigend-Farbe auch bei getauschten Kursfarben`() {
        for (scheme in PriceColorScheme.entries) for (accent in accents) for (dark in listOf(false, true)) {
            for (hc in listOf(false, true)) {
                val success = SnackbarPalette.tone(SnackbarKind.SUCCESS, scheme, dark, hc, accent)
                val key = "${scheme.name}/${accent?.name}/dark=$dark/hc=$hc"
                assertEquals(key, scheme.up(dark, hc, inverted = false, accent = accent), success)
                assertNotEquals(key, scheme.up(dark, hc, inverted = true, accent = accent), success)
            }
        }
        // «Frisch» getauscht: steigend wäre rot, Erfolg bleibt grün
        val fresh = SnackbarPalette.tone(SnackbarKind.SUCCESS, PriceColorScheme.GREEN_RED, false, false, null)
        assertEquals(0xFF127248.toInt(), fresh)
    }

    /** Fehler: Fehlerfarbe der App; mit Farbsehschwäche-Schema dessen Fallend-Ton (Orange), nie getauscht. */
    @Test
    fun `Fehler nimmt die Fehlerfarbe, bei Farbsehschwaeche den Ton des Schemas`() {
        for (scheme in PriceColorScheme.entries) for (accent in accents) for (dark in listOf(false, true)) {
            for (hc in listOf(false, true)) {
                val error = SnackbarPalette.tone(SnackbarKind.ERROR, scheme, dark, hc, accent)
                val expected =
                    if (SnackbarPalette.colorVision(scheme)) scheme.down(dark, hc, inverted = false, accent = accent)
                    else appColorScheme(accent ?: AccentColor.DEFAULT, dark).error.toArgb()
                assertEquals("${scheme.name}/${accent?.name}/dark=$dark/hc=$hc", expected, error)
            }
        }
        assertEquals(
            listOf(PriceColorScheme.BLUE_ORANGE, PriceColorScheme.CALM_COLOR_VISION),
            PriceColorScheme.entries.filter(SnackbarPalette::colorVision),
        )
        // Farbsehschwäche: Blau statt Grün, Orange statt Rot
        val cvd = PriceColorScheme.BLUE_ORANGE
        assertEquals(0xFF064E93.toInt(), SnackbarPalette.tone(SnackbarKind.SUCCESS, cvd, false, false, null))
        assertEquals(0xFFB73900.toInt(), SnackbarPalette.tone(SnackbarKind.ERROR, cvd, false, false, null))
    }

    @Test
    fun `Info hat keinen farbigen Ton`() {
        for (scheme in PriceColorScheme.entries) for (dark in listOf(false, true)) {
            assertNull(SnackbarPalette.tone(SnackbarKind.INFO, scheme, dark, false, null))
        }
    }

    /**
     * Text `onSurface` und Aktion `primary` (auch mit hohem Kontrast) ≥ 4.5:1 auf `surfaceContainerHighest`;
     * Rahmen und Symbol von Erfolg/Fehler als Grafik ≥ 3:1 (WCAG 1.4.11).
     */
    @Test
    fun `Text, Aktion und Rahmen erreichen den Kontrast auf der Flaeche`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(accent, dark)
            val bg = s.surfaceContainerHighest.toArgb()
            val key = "${accent.name}/dark=$dark"
            assertTrue("$key onSurface", ratio(s.onSurface.toArgb(), bg) >= 4.5)
            assertTrue("$key primary", ratio(s.primary.toArgb(), bg) >= 4.5)
            val hcPrimary = withContrast(s.primary, s.surfaceContainerHighest, dark).toArgb()
            assertTrue("$key primary hc", ratio(hcPrimary, bg) >= 4.5)
            for (scheme in PriceColorScheme.entries) for (hc in listOf(false, true)) {
                for (kind in listOf(SnackbarKind.SUCCESS, SnackbarKind.ERROR)) {
                    val tone = SnackbarPalette.tone(kind, scheme, dark, hc, accent)!!
                    val r = ratio(tone, bg)
                    assertTrue("$key ${scheme.name} ${kind.name} hc=$hc: %.2f".format(r), r >= 3.0)
                }
            }
        }
    }

    /** Die Fläche ist im Dunkelmodus dunkel (keine helle Pille `inverseSurface`) und hell im Hellmodus. */
    @Test
    fun `Flaeche passt zum Thema`() {
        for (accent in AccentColor.entries) {
            assertEquals(0xFF353535.toInt(), appColorScheme(accent, dark = true).surfaceContainerHighest.toArgb())
            assertEquals(0xFFE5E2DB.toInt(), appColorScheme(accent, dark = false).surfaceContainerHighest.toArgb())
        }
        val rel = "src/main/java/com/cryptochecker/app/ui/components/AppSnackbar.kt"
        val source = SourceGuard.android(sourceFile(rel), rel)
        assertTrue(source.contains("containerColor = colors.surfaceContainerHighest"))
        assertTrue(source.contains("contentColor = colors.onSurface"))
        assertTrue(!source.contains("colors.inverseSurface"))
    }

    /** Alle Snackbar-Bereiche der App nutzen `AppSnackbarHost` (sonst wieder die helle Material-Pille). */
    @Test
    fun `Bildschirme nutzen AppSnackbarHost`() {
        val rel = "src/main/java/com/cryptochecker/app"
        val root = File(SourceGuard.android(sourceDir(rel), rel))
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "AppSnackbar.kt" }
            .filter { Regex("""(?<![A-Za-z])SnackbarHost\(""").containsMatchIn(it.readText(Charsets.UTF_8)) }
            .map { it.name }.toList()
        assertTrue("SnackbarHost( statt AppSnackbarHost(: $offenders", offenders.isEmpty())
    }

    private fun sourceFile(rel: String): String? = sourceDir(rel)?.let { File(it).readText(Charsets.UTF_8) }

    private fun sourceDir(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, prefix + rel)
                if (f.exists()) return f.path
            }
            dir = dir.parentFile
        }
        return null
    }
}
