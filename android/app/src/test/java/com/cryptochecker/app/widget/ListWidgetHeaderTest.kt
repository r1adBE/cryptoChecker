package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ListWidgetHeaderTest {

    /** Titel «Merkliste»: 60 dp in 13 sp, 50 dp in 11 sp. */
    private val title: (Float) -> Float = { if (it >= 13f) 60f else 50f }

    /** Grobe Breite: 5 dp je Zeichen. «06:42:15 · 840 ms» = 17 Zeichen = 85 dp, «06:42:15» = 40 dp. */
    private val status: (String) -> Float = { it.length * 5f }

    private val fixed = ListWidgetHeader.FIXED_DP + WidgetTextFit.SAFETY_DP

    private fun layout(width: Float, time: String? = "06:42:15", duration: String? = "840 ms") =
        ListWidgetHeader.layout(width.toInt(), time, duration, title, status)

    @Test
    fun statusTextJoinsPresentParts() {
        assertEquals("06:42:15 · 840 ms", ListWidgetHeader.statusText("06:42:15", "840 ms"))
        assertEquals("06:42:15", ListWidgetHeader.statusText("06:42:15", ""))
        assertEquals("840 ms", ListWidgetHeader.statusText(null, "840 ms"))
        assertEquals("", ListWidgetHeader.statusText(null, null))
    }

    @Test
    fun fullWhenRoomy() {
        assertEquals(ListWidgetHeader.Layout(13f, "06:42:15 · 840 ms"), layout(fixed + 60f + 85f))
        assertEquals(ListWidgetHeader.Layout(13f, "06:42:15 · 840 ms"), layout(400f))
    }

    @Test
    fun dropsDurationFirst() {
        assertEquals(ListWidgetHeader.Layout(13f, "06:42:15"), layout(fixed + 60f + 84f))
        assertEquals(ListWidgetHeader.Layout(13f, "06:42:15"), layout(fixed + 60f + 40f))
    }

    @Test
    fun thenShrinksTitle() {
        assertEquals(ListWidgetHeader.Layout(11f, "06:42:15"), layout(fixed + 60f + 39f))
        assertEquals(ListWidgetHeader.Layout(11f, "06:42:15"), layout(fixed + 50f + 40f))
    }

    @Test
    fun thenDropsTimeButKeepsTitle() {
        assertEquals(ListWidgetHeader.Layout(11f, ""), layout(fixed + 50f + 39f))
        assertEquals(ListWidgetHeader.Layout(11f, ""), layout(fixed + 50f))
        // Nicht einmal der kleine Titel passt: trotzdem Titel, ohne Uhrzeit
        assertEquals(ListWidgetHeader.Layout(11f, ""), layout(fixed + 10f))
    }

    @Test
    fun unknownWidthShowsEverything() {
        assertEquals(ListWidgetHeader.Layout(13f, "06:42:15 · 840 ms"), layout(0f))
    }

    @Test
    fun withoutTimeOnlyDurationCanDrop() {
        // Noch nie aktualisiert: nur die Dauer; fällt sie weg, bleibt der Titel in 13 sp
        assertEquals(ListWidgetHeader.Layout(13f, "840 ms"), layout(fixed + 60f + 30f, time = null))
        assertEquals(ListWidgetHeader.Layout(13f, ""), layout(fixed + 60f + 29f, time = null))
        assertEquals(ListWidgetHeader.Layout(13f, ""), layout(400f, time = null, duration = null))
    }

    @Test
    fun outdatedKeepsWordBeforeDroppingStatus() {
        // «veraltet · 06:42» = 16 Zeichen = 80 dp, «veraltet» = 40 dp
        fun stale(width: Float) =
            ListWidgetHeader.layout(width.toInt(), "veraltet · 06:42", null, title, status, fallback = "veraltet")
        assertEquals(ListWidgetHeader.Layout(13f, "veraltet · 06:42"), stale(fixed + 60f + 80f))
        assertEquals(ListWidgetHeader.Layout(11f, "veraltet · 06:42"), stale(fixed + 50f + 80f))
        assertEquals(ListWidgetHeader.Layout(11f, "veraltet"), stale(fixed + 50f + 79f))
        assertEquals(ListWidgetHeader.Layout(11f, "veraltet"), stale(fixed + 50f + 40f))
        assertEquals(ListWidgetHeader.Layout(11f, ""), stale(fixed + 50f + 39f))
    }
}
