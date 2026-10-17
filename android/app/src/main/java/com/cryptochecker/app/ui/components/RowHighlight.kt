package com.cryptochecker.app.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Welche kleinen Zeichen in den Zeilen gerade in der Themenfarbe stehen: Stern (★) und Blitz (⚡)
 * sind sonst weiss (Textfarbe) und werden nur farbig, solange der passende Gruppen-Chip gewählt
 * ist (★ = Favoriten, ⚡ = «Hier passiert gerade etwas»). Ebenso das Art-Symbol vor der Glocke
 * (Token = Raute, TradFi = Bank, Alpha = «α»): in der Themenfarbe nur mit gewähltem Chip
 * «Token» ([tokens]), «TradFi» ([tradFi]) bzw. «Alpha» ([alpha]). Wie `RowHighlight` (iOS).
 */
data class RowHighlight(
    val favorites: Boolean = false,
    val activity: Boolean = false,
    val tokens: Boolean = false,
    val tradFi: Boolean = false,
    val alpha: Boolean = false,
)

val LocalRowHighlight = compositionLocalOf { RowHighlight() }

/**
 * Hauptschalter «Kursmitteilungen» (Einstellungen › Alarme): Die Zeile zeigt die schlichte Glocke nur, wenn er
 * und der Schalter des Paars an sind, sonst die durchgestrichene. Wie iOS `\.priceNotificationsOn`.
 */
val LocalPriceNotificationsOn = compositionLocalOf { true }
