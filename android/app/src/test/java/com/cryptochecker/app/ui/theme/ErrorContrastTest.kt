package com.cryptochecker.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fehlerfarben: hell wie dunkel warmes Orange-Rot, für jede Akzentfarbe gleich.
 * Gelesen aus den echten Schemata [appColorScheme], nicht aus einer Kopie der Werte.
 * Wie iOS `WarmSurfaceAndScaleContrastTests.testErrorTokens…`.
 */
class ErrorContrastTest {

    private fun ratio(a: Int, b: Int) = WidgetContrast.ratio(a, b)

    @Test
    fun `helle Fehlerfarben wie spezifiziert, fuer jede Akzentfarbe`() {
        for (accent in AccentColor.entries) {
            val s = appColorScheme(accent, dark = false)
            assertEquals(accent.name, 0xFFB13419.toInt(), s.error.toArgb())
            assertEquals(accent.name, 0xFFFFFFFF.toInt(), s.onError.toArgb())
            assertEquals(accent.name, 0xFFFFDBD0.toInt(), s.errorContainer.toArgb())
            assertEquals(accent.name, 0xFF4A1C10.toInt(), s.onErrorContainer.toArgb())
        }
    }

    @Test
    fun `dunkle Fehlerfarben unveraendert`() {
        for (accent in AccentColor.entries) {
            val s = appColorScheme(accent, dark = true)
            assertEquals(accent.name, 0xFFFF5833.toInt(), s.error.toArgb())
            assertEquals(accent.name, 0xFF000000.toInt(), s.onError.toArgb())
            assertEquals(accent.name, 0xFF4A1C10.toInt(), s.errorContainer.toArgb())
            assertEquals(accent.name, 0xFFFFDBD0.toInt(), s.onErrorContainer.toArgb())
        }
    }

    /** Fehlertext ≥ 4.5:1 auf jeder hellen Fläche des Schemas; Schrift auf error und errorContainer ebenso. */
    @Test
    fun `helle Fehlerfarben erreichen AA`() {
        for (accent in AccentColor.entries) {
            val s = appColorScheme(accent, dark = false)
            val error = s.error.toArgb()
            val surfaces = listOf(
                s.surfaceContainerLowest, s.surface, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest, s.surfaceVariant,
            ).map { it.toArgb() }
            for (bg in surfaces) {
                val r = ratio(error, bg)
                assertTrue("${accent.name} error on %06X: %.2f".format(bg and 0xFFFFFF, r), r >= 4.5)
            }
            assertTrue("${accent.name} onError", ratio(s.onError.toArgb(), error) >= 4.5)
            assertTrue("${accent.name} onErrorContainer", ratio(s.onErrorContainer.toArgb(), s.errorContainer.toArgb()) >= 4.5)
            assertTrue("${accent.name} error on errorContainer", ratio(error, s.errorContainer.toArgb()) >= 4.5)
        }
    }

    @Test
    fun `dunkle Fehlerfarben erreichen AA auf dem Grund`() {
        for (accent in AccentColor.entries) {
            val s = appColorScheme(accent, dark = true)
            val error = s.error.toArgb()
            for (bg in listOf(s.surfaceContainerLowest, s.surface, s.surfaceContainerLow, s.surfaceContainer)) {
                assertTrue("${accent.name} dark", ratio(error, bg.toArgb()) >= 4.5)
            }
            assertTrue("${accent.name} onError dark", ratio(s.onError.toArgb(), error) >= 4.5)
            assertTrue("${accent.name} onErrorContainer dark", ratio(s.onErrorContainer.toArgb(), s.errorContainer.toArgb()) >= 4.5)
        }
    }

    /** Hoher Kontrast übernimmt die Fehlerfarbe (#B13419 erreicht AA schon auf der dunkelsten Karte). */
    @Test
    fun `hoher Kontrast setzt die Fehlerfarbe nicht neu`() {
        val source = SourceGuard.android(sourceFile("src/main/java/com/cryptochecker/app/ui/theme/Theme.kt"), "ui/theme/Theme.kt")
        val start = source.indexOf("private fun ColorScheme.withHighContrast(")
        assertTrue("withHighContrast fehlt", start >= 0)
        val end = source.indexOf("\n}", start)
        val body = source.substring(start, end)
        assertTrue("withHighContrast überschreibt error", !Regex("""\berror(Container)?\s*=""").containsMatchIn(body))
    }

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
}
