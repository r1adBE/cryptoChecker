package com.cryptochecker.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Coin-Listen wie eine Einheit (Merkliste, Start-Auswahl, Heute auffällig, Portfolio): jede Zeile
 * eine eigene Karte mit nur [Gap] Abstand, aussen stark gerundet (oben an der ersten, unten an der
 * letzten Zeile), innen nur leicht — so sieht man die einzelnen Zeilen, die Liste wirkt aber als
 * Ganzes. Wie die Listen in den Einstellungen von Android 16 und iOS; wie `ListSegment` (iOS).
 */
object ListSegment {
    /** Fuge zwischen zwei Zeilen. */
    val Gap = 2.dp

    /** Abstand zu anderen Elementen über bzw. unter der Liste (statt 8 dp im Ganzen). */
    val Spacing = 8.dp - Gap

    /** Logo in allen Coin-Listen gleich gross. */
    val Logo = 36.dp

    private val Outer = 16.dp
    private val Inner = 4.dp

    /** Form der Zeile [index] von [count] Zeilen. */
    fun shape(index: Int, count: Int): Shape {
        val top = if (index <= 0) Outer else Inner
        val bottom = if (index >= count - 1) Outer else Inner
        return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    }
}
