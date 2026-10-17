package com.cryptochecker.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetScreenGateTest {

    @Test
    fun drawsWhenScreenOn() {
        val gate = WidgetScreenGate()
        assertTrue(gate.onTick(deferWhenOff = true, interactive = true))
        assertFalse(gate.isDirty)
        assertFalse(gate.onScreenOn())
    }

    @Test
    fun defersWhileOffAndDrawsOnceOnScreenOn() {
        val gate = WidgetScreenGate()
        assertFalse(gate.onTick(deferWhenOff = true, interactive = false))
        assertFalse(gate.onTick(deferWhenOff = true, interactive = false))
        assertTrue(gate.isDirty)
        assertTrue(gate.onScreenOn())
        // Nur einmal
        assertFalse(gate.onScreenOn())
    }

    @Test
    fun nextInteractiveTickClearsPending() {
        val gate = WidgetScreenGate()
        gate.onTick(deferWhenOff = true, interactive = false)
        assertTrue(gate.onTick(deferWhenOff = true, interactive = true))
        assertFalse(gate.onScreenOn())
    }

    @Test
    fun backgroundJobAlwaysDraws() {
        val gate = WidgetScreenGate()
        assertTrue(gate.onTick(deferWhenOff = false, interactive = false))
        assertFalse(gate.isDirty)
    }
}
