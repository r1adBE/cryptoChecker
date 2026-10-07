package com.cryptochecker.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Eine ruhige Formstufe: Felder und Menüs 12 dp, Karten 16 dp, grosse
 * Flächen 20 dp, Dialoge und Blätter 28 dp.
 */
val appShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
