package com.cryptochecker.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TabLabelFitTest {

    @Test
    fun `normale Schrift bleibt unveraendert`() {
        assertEquals(listOf(36f), TabLabelFit.candidates(targetPx = 36f, floorPx = 36f))
        assertEquals(listOf(30f), TabLabelFit.candidates(targetPx = 30f, floorPx = 36f))
    }

    @Test
    fun `grosse Schrift hoechstens bis 80 Prozent`() {
        val sizes = TabLabelFit.candidates(targetPx = 100f, floorPx = 36f)
        assertEquals(TabLabelFit.STEPS + 1, sizes.size)
        assertEquals(100f, sizes.first(), 0.001f)
        assertEquals(80f, sizes.last(), 0.001f)
    }

    @Test
    fun `nie unter die Groesse bei normaler Schrift`() {
        val sizes = TabLabelFit.candidates(targetPx = 40f, floorPx = 36f)
        assertEquals(36f, sizes.last(), 0.001f)
        assertEquals(true, sizes.all { it >= 36f })
    }

    @Test
    fun `erste passende Groesse sonst die kleinste`() {
        val sizes = listOf(100f, 95f, 90f, 85f, 80f)
        assertEquals(90f, TabLabelFit.pick(sizes) { it <= 92f }, 0f)
        assertEquals(80f, TabLabelFit.pick(sizes) { false }, 0f)
    }
}
