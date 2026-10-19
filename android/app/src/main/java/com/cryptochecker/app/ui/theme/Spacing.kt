package com.cryptochecker.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Abstände im 4-dp-Raster (wie `Spacing` in iOS): Innenabstände, Lücken zwischen
 * Elementen und Ränder nehmen eine dieser Stufen. Kleinere Feinabstimmungen (1–3 dp)
 * und Masse von Charts bleiben eigene Werte.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /**
     * Freiraum unter der letzten Zeile einer Liste mit schwebendem «+»-Knopf (`AppFab`, 56 dp
     * + Rand + Schatten): 3 × [xxl], damit der Knopf nichts verdeckt. Keine Rasterstufe, sondern
     * daraus abgeleitet (darum nicht als eigene dp-Zahl) — wie `Spacing.fabClearance` (iOS).
     */
    val fabClearance = xxl * 3
}
