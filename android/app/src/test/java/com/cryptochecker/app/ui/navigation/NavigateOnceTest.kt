package com.cryptochecker.app.ui.navigation

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doppel-Tipp legt Alarm-, «Alle Alarme»- und «Paar hinzufügen»-Seite nur einmal auf den Stapel. */
class NavigateOnceTest {

    @Test
    fun onlyTheFrontmostPageMayNavigate() {
        assertTrue(NavigateOnce.allowed(Lifecycle.State.RESUMED))
        // Nach dem ersten Tipp steht die alte Seite während der Überblendung auf STARTED
        assertFalse(NavigateOnce.allowed(Lifecycle.State.STARTED))
        assertFalse(NavigateOnce.allowed(Lifecycle.State.CREATED))
        assertFalse(NavigateOnce.allowed(Lifecycle.State.DESTROYED))
    }

    @Test
    fun secondTapDuringTransitionCreatesNoSecondEntry() {
        // Nachbau des Ablaufs: der erste Tipp navigiert und stuft die Seite auf STARTED herab
        var state = Lifecycle.State.RESUMED
        val backStack = mutableListOf<String>()
        repeat(2) {
            if (NavigateOnce.allowed(state)) {
                backStack += "alarms"
                state = Lifecycle.State.STARTED
            }
        }
        assertEquals(1, backStack.size)
    }
}
