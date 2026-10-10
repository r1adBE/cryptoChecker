package com.cryptochecker.app.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Welche kleinen Zeichen in den Zeilen gerade in der Themenfarbe stehen: Stern (★) und Blitz (⚡)
 * sind sonst weiss (Textfarbe) und werden nur farbig, solange der passende Gruppen-Chip gewählt
 * ist (★ = Favoriten, ⚡ = «Hier passiert gerade etwas»). Wie `RowHighlight` (iOS).
 */
data class RowHighlight(val favorites: Boolean = false, val activity: Boolean = false)

val LocalRowHighlight = compositionLocalOf { RowHighlight() }
