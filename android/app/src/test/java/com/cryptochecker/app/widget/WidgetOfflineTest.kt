package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.widget.ListWidgetHeader
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.domain.widget.WidgetTextFit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Keine Verbindung» in den Widgets: Reihenfolge offline > veraltet > Uhrzeit ([WidgetStatusRule])
 * und die enge Stufe des Listenkopfs. Was die Widgets zeichnen (Zeit-Zeile ohne Netz, Neuzeichnen bei Rückkehr, eine
 * Netzabfrage je Zeichnen, erster Auftritt), prüft `WidgetDrawDeviceTest` auf dem Gerät; der Knopf ohne Netz:
 * `WidgetRefreshStateTest`.
 */
class WidgetOfflineTest {

    @Test
    fun `rule puts offline before outdated before time`() {
        assertEquals(WidgetStatusRule.Kind.OFFLINE, WidgetStatusRule.kind(online = false, outdated = true, time = 1L))
        assertEquals(WidgetStatusRule.Kind.OFFLINE_NO_TIME, WidgetStatusRule.kind(online = false, outdated = false, time = 0L))
        assertEquals(WidgetStatusRule.Kind.OUTDATED, WidgetStatusRule.kind(online = true, outdated = true, time = 1L))
        assertEquals(WidgetStatusRule.Kind.TIME, WidgetStatusRule.kind(online = true, outdated = false, time = 1L))
    }

    /** Eng: «Keine Verbindung» bleibt als Ersatz stehen, bevor rechts nichts mehr steht (wie «veraltet»). */
    @Test
    fun `list header keeps the offline word when the time no longer fits`() {
        val title: (Float) -> Float = { if (it >= 14f) 60f else 50f }
        val status: (String) -> Float = { it.length * 5f }
        val fixed = ListWidgetHeader.FIXED_DP + WidgetTextFit.SAFETY_DP
        val full = "Keine Verbindung · Stand 14:05"
        fun layout(width: Float) = ListWidgetHeader.layout(width.toInt(), full, null, title, status, fallback = "Keine Verbindung")
        assertEquals(ListWidgetHeader.Layout(14f, full), layout(fixed + 60f + full.length * 5f))
        assertEquals(ListWidgetHeader.Layout(12f, "Keine Verbindung"), layout(fixed + 50f + 16 * 5f))
        assertEquals(ListWidgetHeader.Layout(12f, ""), layout(fixed + 50f))
    }
}
