package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V4-02: «Was bewegt sich mit?» ohne Kerzen – offline bzw. Zeitgrenze ist «nicht abrufbar» (mit «Erneut versuchen»),
 * nur sonst «keine Stundenkerzen für dieses Paar». iOS: WhyCandlesFailureTests.
 */
class WhyCandlesFailureTest {

    @Test
    fun offlineGehtVor() {
        assertEquals(WhyCandlesFailure.OFFLINE, WhyCandlesFailure.of(online = false, timedOut = false))
        assertEquals(WhyCandlesFailure.OFFLINE, WhyCandlesFailure.of(online = false, timedOut = true))
    }

    @Test
    fun zeitgrenzeIstNichtAbrufbar() {
        assertEquals(WhyCandlesFailure.TIMEOUT, WhyCandlesFailure.of(online = true, timedOut = true))
    }

    @Test
    fun geantwortetOhneKerzenIstKeinFehler() {
        assertNull(WhyCandlesFailure.of(online = true, timedOut = false))
        // Standard des Berichts: kein Fehler
        assertNull(ActivityAnalyzer.notLive(1L).candlesFailure)
    }
}
