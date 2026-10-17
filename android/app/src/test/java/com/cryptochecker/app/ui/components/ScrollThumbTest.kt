package com.cryptochecker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollThumbTest {

    @Test
    fun `thumb covers the visible rows proportionally`() {
        // 100 Zeilen, 10 sichtbar ab Zeile 20, Spur 1000 px → oben 200, Höhe 100
        assertEquals(ScrollThumb.Span(200f, 100f), ScrollThumb.span(20, 10, 100, 1000f))
        assertEquals(ScrollThumb.Span(0f, 1000f), ScrollThumb.span(0, 5, 5, 1000f))
    }

    @Test
    fun `nothing to draw without rows, visible rows or height`() {
        assertNull(ScrollThumb.span(null, 0, 10, 500f))
        assertNull(ScrollThumb.span(0, 3, 0, 500f))
        assertNull(ScrollThumb.span(0, 0, 10, 500f))
        assertNull(ScrollThumb.span(0, 3, 10, 0f))
    }

    @Test
    fun `thumb never runs past the track`() {
        val span = ScrollThumb.span(95, 10, 100, 1000f)!!
        assertEquals(950f, span.top, 0f)
        assertEquals(50f, span.height, 0f)
    }
}
