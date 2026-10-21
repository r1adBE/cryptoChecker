package com.cryptochecker.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ziele von aussen nur mit dem Geheimnis der Installation ([OpenTargetGuard], gleich wie iOS). */
class OpenTargetGuardTest {

    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun shortcutTargetsWorkWithoutToken() {
        // Statische App-Verknüpfungen können kein Geheimnis mitgeben
        for (target in listOf("add", "alarms", "cycle")) {
            assertTrue(target, OpenTargetGuard.accepts(target, token = null, expected = secret))
        }
    }

    @Test
    fun watchTargetWorksWithoutToken() {
        // Wie MainActivity.EXTRA_WATCH_ID: nur die Merkliste mit dem Blatt eines Paars
        assertTrue(OpenTargetGuard.accepts("watch/12", token = null, expected = secret))
        assertFalse(OpenTargetGuard.accepts("watch/0", token = null, expected = secret))
        assertFalse(OpenTargetGuard.accepts("watch/x", token = null, expected = secret))
    }

    @Test
    fun portfolioNeedsMatchingToken() {
        assertFalse(OpenTargetGuard.accepts("portfolio", token = null, expected = secret))
        assertFalse(OpenTargetGuard.accepts("portfolio", token = "falsch", expected = secret))
        assertFalse(OpenTargetGuard.accepts("portfolio", token = "", expected = ""))
        assertTrue(OpenTargetGuard.accepts("portfolio", token = secret, expected = secret))
    }

    @Test
    fun otherTargetsNeedToken() {
        val targets = listOf("cycle/macro", "why/5", "alarm/3/7", "unbekannt")
        for (target in targets) {
            assertFalse(target, OpenTargetGuard.accepts(target, token = null, expected = secret))
            assertTrue(target, OpenTargetGuard.accepts(target, token = secret, expected = secret))
        }
    }
}
