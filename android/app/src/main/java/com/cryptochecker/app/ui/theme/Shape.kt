package com.cryptochecker.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Eine ruhige Formstufe: Felder und Menüs 12 dp, Karten auf dem Bildschirm 20 dp (`large`,
 * [com.cryptochecker.app.ui.components.CardLevel.Top]), Karten und Zeilen in einer Karte bzw.
 * Liste 16 dp (`medium`, `CardLevel.Nested`), Dialoge und Blätter 28 dp ([ChipShape] 8 dp nur für
 * Chips, Tooltips und Chart-Marken). Gleiche Werte: iOS `Radius`. Ausnahmen: Farbmuster 16 × 16 (4 dp).
 */
val appShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Kleine Stufe 8 dp: nur Chips, Tooltips und Marken im Chart (wie iOS `Radius.chip`). */
val ChipShape = RoundedCornerShape(8.dp)
