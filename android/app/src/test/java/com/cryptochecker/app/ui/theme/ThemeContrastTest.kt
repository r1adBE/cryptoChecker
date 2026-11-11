package com.cryptochecker.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.cryptochecker.app.domain.settings.AccentColor
import com.cryptochecker.app.widget.WidgetContrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG-Kontrast der Themenfarben aus den echten Schemata [appColorScheme], ergänzend zu [AccentContrastTest]
 * (Akzent, warme Flächen) und [ErrorContrastTest]: Haupt-, Neben- und Warntext in Hell und Dunkel auf allen
 * Flächen ≥ 4.5:1; auf den Auswahl-Tönungen ([AppColors.accentTint], bis 0.16 mit Schrift) Schrift ≥ 4.5:1 und
 * Symbole in primary/outline ≥ 3:1; gesperrte Elemente ([DisabledColors]) ≥ 3:1. Gleiche Paare:
 * android/tools/check_theme_contrast.py, iOS `ThemeContrastTests`.
 */
class ThemeContrastTest {

    private fun ratio(a: Color, b: Color) = WidgetContrast.ratio(a.toArgb(), b.toArgb())

    private fun surfaces(dark: Boolean, accent: AccentColor): List<Color> = appColorScheme(accent, dark).run {
        listOf(
            surfaceContainerLowest, surface, surfaceContainerLow, surfaceContainer,
            surfaceContainerHigh, surfaceContainerHighest,
        ) + if (dark) listOf(DarkSelectedContainer) else listOf(Color(WIDGET_DEEP_LIGHT))
    }

    @Test
    fun `Haupt- Neben- und Warntext erreichen AA auf allen Flaechen`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(accent, dark)
            val warning = Color(if (dark) WARNING_DARK else WARNING_TEXT_LIGHT)
            for (bg in surfaces(dark, accent)) {
                for ((name, fg) in listOf("onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant,
                    "warningText" to warning)) {
                    val r = ratio(fg, bg)
                    val where = "%06X".format(bg.toArgb() and 0xFFFFFF)
                    assertTrue("${accent.name}/dark=$dark $name auf $where: %.2f".format(r), r >= 4.5)
                }
            }
        }
    }

    /** Schrift und Symbole auf der Auswahl-Tönung (wie [AppColors.accentTint]) über Seite, Blatt und Menü. */
    @Test
    fun `Schrift und Symbole auf der Auswahl-Toenung bis 0_16`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(accent, dark)
            val tintColor =
                if (dark) s.onSurface.copy(alpha = TEXT_TINT_MAX * 0.8f) else s.primary.copy(alpha = TEXT_TINT_MAX)
            for (base in listOf(s.surfaceContainerLowest, s.surface, s.surfaceContainerLow, s.surfaceContainer)) {
                val bg = tintColor.compositeOver(base)
                val where = "${accent.name}/dark=$dark auf %06X".format(bg.toArgb() and 0xFFFFFF)
                for (fg in listOf(s.onSurface, s.onSurfaceVariant)) {
                    assertTrue("Schrift $where: %.2f".format(ratio(fg, bg)), ratio(fg, bg) >= 4.5)
                }
                for (fg in listOf(s.primary, s.outline)) {
                    assertTrue("Symbol $where: %.2f".format(ratio(fg, bg)), ratio(fg, bg) >= 3.0)
                }
            }
        }
    }

    /**
     * Gewählte Zeile mit Tönung 0.3 (DropdownField): Schrift onSurface ≥ 4.5:1, Stern voll (primary) bzw. leer
     * (onSurfaceVariant) ≥ 3:1 — outline hielt dort unter 3:1.
     */
    @Test
    fun `gewaehlte Zeile mit Toenung 0_3 und Stern`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(accent, dark)
            val tintColor = if (dark) s.onSurface.copy(alpha = ROW_TINT * 0.8f) else s.primary.copy(alpha = ROW_TINT)
            for (base in listOf(s.surfaceContainerLowest, s.surface, s.surfaceContainerLow, s.surfaceContainer)) {
                val bg = tintColor.compositeOver(base)
                val where = "${accent.name}/dark=$dark auf %06X".format(bg.toArgb() and 0xFFFFFF)
                assertTrue("Schrift $where", ratio(s.onSurface, bg) >= 4.5)
                for (fg in listOf(s.primary, s.onSurfaceVariant)) {
                    assertTrue("Stern $where: %.2f".format(ratio(fg, bg)), ratio(fg, bg) >= 3.0)
                }
            }
        }
    }

    /**
     * Gesperrte Elemente (O-04, [DisabledColors]): Schrift und Symbole in onSurface · [DISABLED_CONTENT_ALPHA] ≥ 3:1 auf
     * allen Flächen und auf der gesperrten Knopffläche (onSurface · [DISABLED_CONTAINER_ALPHA] darüber), blasser als
     * Nebentext; gesperrter Schalter (Knopf in surface auf der Spur) ≥ 3:1; abgeblendete Zeilen ([DISABLED_ROW_ALPHA]) mit
     * Haupt- und Nebentext ≥ 3:1.
     */
    @Test
    fun `gesperrte Schrift Symbole und Schalter erreichen 3 zu 1`() {
        for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(accent, dark)
            for (bg in surfaces(dark, accent)) {
                val where = "${accent.name}/dark=$dark auf %06X".format(bg.toArgb() and 0xFFFFFF)
                val content = s.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA).compositeOver(bg)
                assertTrue("gesperrt $where: %.2f".format(ratio(content, bg)), ratio(content, bg) >= 3.0)
                assertTrue("blasser als Nebentext $where", ratio(content, bg) < ratio(s.onSurfaceVariant, bg))
                val container = s.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA).compositeOver(bg)
                val onContainer = s.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA).compositeOver(container)
                assertTrue("gesperrter Knopf $where", ratio(onContainer, container) >= 3.0)
                assertTrue("gesperrter Schalter $where", ratio(s.surface, content) >= 3.0)
                for (fg in listOf(s.onSurface, s.onSurfaceVariant)) {
                    val row = fg.copy(alpha = DISABLED_ROW_ALPHA).compositeOver(bg)
                    assertTrue("abgeblendete Zeile $where: %.2f".format(ratio(row, bg)), ratio(row, bg) >= 3.0)
                }
            }
        }
    }

    private companion object {
        /** Tönung der gewählten Zeile (DropdownField, iOS Coin-Liste). */
        const val ROW_TINT = 0.3f
        /** Höchste Deckkraft einer Tönung, auf der Schrift steht (Auswahl-Listen, Hinweiskarten, Merkliste-Zeile). */
        const val TEXT_TINT_MAX = 0.16f
        const val WIDGET_DEEP_LIGHT = 0xFFE7E4DD
        const val WARNING_TEXT_LIGHT = 0xFF8A5300
        const val WARNING_DARK = 0xFFFFC94D
    }
}
