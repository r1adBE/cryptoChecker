@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deckkraft gesperrter Schrift und Symbole auf `onSurface` (Material: 0.38 — hell nur 2.3:1, dunkel 2.7–3.1:1). Mit
 * 0.55 ≥ 3:1 auf allen Flächen in Hell und Dunkel, auch auf der gesperrten Knopffläche ([DISABLED_CONTAINER_ALPHA]);
 * grau statt Akzentfarbe, deutlich blasser als Nebentext (≥ 4.5:1). Gleicher Wert: iOS `ColorTokens.disabledContentAlpha`,
 * geprüft von android/tools/check_theme_contrast.py, [ThemeContrastTest] und iOS `ThemeContrastTests`.
 */
const val DISABLED_CONTENT_ALPHA = 0.55f

/** Gesperrte Knopffläche: `onSurface` · 0.12 (wie Material). */
const val DISABLED_CONTAINER_ALPHA = 0.12f

/**
 * Ganze Zeile abgeblendet (Schrift in `onSurface` und `onSurfaceVariant`, ohne gesperrten Schalter darin): 0.7 hält
 * Nebentext ≥ 3:1 (0.5 hielt nur 2.3:1). Wie iOS `ColorTokens.disabledRowAlpha`.
 */
const val DISABLED_ROW_ALPHA = 0.7f

/**
 * Farben gesperrter Bedienelemente (O-04): Schrift, Symbole, Schalter und Ränder in [content] (≥ 3:1), Flächen in
 * [container]. Jede Stelle, die ein Material-Element mit `enabled = …` sperren kann, übergibt die passenden Farben —
 * sonst gälte Materials 0.38.
 */
object DisabledColors {

    /** Gesperrte Schrift und Symbole: `onSurface` · [DISABLED_CONTENT_ALPHA]. */
    val content: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)

    /** Gesperrte Knopffläche: `onSurface` · [DISABLED_CONTAINER_ALPHA]. */
    val container: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)

    @Composable
    fun textButton(): ButtonColors = ButtonDefaults.textButtonColors(disabledContentColor = content)

    @Composable
    fun button(): ButtonColors =
        ButtonDefaults.buttonColors(disabledContainerColor = container, disabledContentColor = content)

    @Composable
    fun filledTonalButton(): ButtonColors =
        ButtonDefaults.filledTonalButtonColors(disabledContainerColor = container, disabledContentColor = content)

    @Composable
    fun filledTonalIconButton(): IconButtonColors =
        IconButtonDefaults.filledTonalIconButtonColors(disabledContainerColor = container, disabledContentColor = content)

    @Composable
    fun filterChip(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        disabledLabelColor = content,
        disabledLeadingIconColor = content,
        disabledTrailingIconColor = content,
    )

    @Composable
    fun menuItem(): MenuItemColors = MenuDefaults.itemColors(
        disabledTextColor = content,
        disabledLeadingIconColor = content,
        disabledTrailingIconColor = content,
    )

    @Composable
    fun segmentedButton(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
        disabledActiveContentColor = content,
        disabledInactiveContentColor = content,
        disabledActiveBorderColor = content,
        disabledInactiveBorderColor = content,
    )

    @Composable
    fun outlinedTextField(): TextFieldColors = OutlinedTextFieldDefaults.colors(
        disabledTextColor = content,
        disabledLabelColor = content,
        disabledPlaceholderColor = content,
        disabledBorderColor = content,
        disabledLeadingIconColor = content,
        disabledTrailingIconColor = content,
        disabledSupportingTextColor = content,
        disabledPrefixColor = content,
        disabledSuffixColor = content,
    )

    /**
     * Gesperrter Schalter: an = Spur [content] mit Knopf in der Fläche (Knopf gegen Spur ≥ 3:1), aus = Rand und Knopf in
     * [content] — grau statt Akzentfarbe, Material blendete beides auf 0.12/0.38 ab (Spur und Rand unter 3:1).
     */
    @Composable
    fun switch(): SwitchColors {
        val scheme = MaterialTheme.colorScheme
        return SwitchDefaults.colors(
            disabledCheckedThumbColor = scheme.surface,
            disabledCheckedTrackColor = content,
            disabledCheckedBorderColor = content,
            disabledCheckedIconColor = content,
            disabledUncheckedThumbColor = content,
            disabledUncheckedTrackColor = scheme.surfaceContainerHighest,
            disabledUncheckedBorderColor = content,
            disabledUncheckedIconColor = scheme.surfaceContainerHighest,
        )
    }
}
