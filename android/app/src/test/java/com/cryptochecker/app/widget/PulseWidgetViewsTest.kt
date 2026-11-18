package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ansichten des Pulse-Widgets ([PulseWidgetView], [PulseWidgetMovers]). */
class PulseWidgetViewsTest {

    @Test
    fun `mover rows follow the height`() {
        assertEquals(PulseWidgetMovers.MAX_ROWS, PulseWidgetMovers.rows(0))
        assertEquals(1, PulseWidgetMovers.rows(100))
        assertEquals(2, PulseWidgetMovers.rows(82 + 44))
        assertEquals(3, PulseWidgetMovers.rows(400))
    }

    @Test
    fun `unknown or missing view is the impulse`() {
        assertEquals(PulseWidgetView.IMPULSE, PulseWidgetView.fromName(null))
        assertEquals(PulseWidgetView.IMPULSE, PulseWidgetView.fromName("OTHER"))
        assertEquals(PulseWidgetView.HALVING, PulseWidgetView.fromName("HALVING"))
    }
}
