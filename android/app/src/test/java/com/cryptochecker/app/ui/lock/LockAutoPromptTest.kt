package com.cryptochecker.app.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selbst-Abfrage des Portfolio-Sperr-Zustands: einmal je Erscheinen, nicht erneut nach dem Drehen. */
class LockAutoPromptTest {

    @Test
    fun asksOnFirstAppearanceExactlyOnce() {
        val prompt = LockAutoPrompt()
        assertTrue(prompt.take())
        assertFalse(prompt.take())
    }

    @Test
    fun afterCancelAndRotationNoSecondPrompt() {
        val prompt = LockAutoPrompt()
        assertTrue(prompt.take())
        prompt.left(changingConfigurations = true)
        assertFalse(prompt.take())
    }

    @Test
    fun askAgainAfterTabChangeOrRelock() {
        val prompt = LockAutoPrompt()
        assertTrue(prompt.take())
        prompt.left(changingConfigurations = false)
        assertTrue(prompt.take())
    }
}
