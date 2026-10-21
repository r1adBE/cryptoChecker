package com.cryptochecker.app.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selbst-Abfrage des Portfolio-Sperr-Zustands: einmal je Erscheinen, nicht erneut nach dem Drehen. */
class LockAutoPromptTest {

    @Test
    fun fragtBeimErstenErscheinenGenauEinmal() {
        val prompt = LockAutoPrompt()
        assertTrue(prompt.take())
        assertFalse(prompt.take())
    }

    @Test
    fun nachAbbruchUndDrehenKeineZweiteAbfrage() {
        val prompt = LockAutoPrompt()
        assertTrue(prompt.take())
        prompt.left(changingConfigurations = true)
        assertFalse(prompt.take())
    }

    @Test
    fun nachTabWechselOderErneutemSperrenWiederFragen() {
        val prompt = LockAutoPrompt()
        assertTrue(prompt.take())
        prompt.left(changingConfigurations = false)
        assertTrue(prompt.take())
    }
}
