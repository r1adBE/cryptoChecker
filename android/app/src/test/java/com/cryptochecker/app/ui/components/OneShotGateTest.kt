package com.cryptochecker.app.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Einmal-Rückmeldung: nach einem Neuaufbau (Drehen, Dunkelmodus) dieselbe Meldung nicht nochmals vibrieren lassen. */
class OneShotGateTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    /** Sichern und wiederherstellen wie beim Neuaufbau der Activity. */
    private fun recreate(gate: OneShotGate): OneShotGate {
        val saved = with(OneShotGate.Saver) { scope.save(gate) } ?: return OneShotGate()
        return OneShotGate.Saver.restore(saved)!!
    }

    @Test
    fun newMessageCountsOnce() {
        val gate = OneShotGate()
        assertTrue(gate.claim("ADDED"))
        assertFalse(gate.claim("ADDED"))
    }

    @Test
    fun afterRecreationNoRepeat() {
        val gate = OneShotGate()
        assertTrue(gate.claim(2))
        assertFalse(recreate(gate).claim(2))
    }

    @Test
    fun otherMessageAfterRecreationCounts() {
        val gate = OneShotGate()
        assertTrue(gate.claim(1))
        assertTrue(recreate(gate).claim(2))
    }

    @Test
    fun nullResets() {
        val gate = OneShotGate()
        assertTrue(gate.claim(true))
        assertFalse(gate.claim(null))
        assertTrue(gate.claim(true))
    }

    @Test
    fun withoutShownMessageNothingIsSaved() {
        val gate = OneShotGate()
        assertNull(with(OneShotGate.Saver) { scope.save(gate) })
        gate.claim("x")
        assertEquals("x", with(OneShotGate.Saver) { scope.save(gate) })
    }
}
