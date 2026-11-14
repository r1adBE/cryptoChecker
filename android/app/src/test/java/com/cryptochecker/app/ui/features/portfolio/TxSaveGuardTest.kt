package com.cryptochecker.app.ui.features.portfolio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transaktion speichern: kein zweites Anlegen derselben Eingaben (B3-14, wie iOS `guard !saving`). */
class TxSaveGuardTest {

    @Test
    fun `second save while writing is ignored`() {
        val guard = TxSaveGuard()
        assertTrue(guard.begin("a"))
        assertTrue(guard.busy.value)
        // Doppeltipp bzw. neu aufgebautes Blatt nach dem Drehen, während geschrieben wird
        assertFalse(guard.begin("a"))
        assertFalse(guard.begin("b"))
    }

    @Test
    fun `saved session never saves again and failed one may retry`() {
        val guard = TxSaveGuard()
        assertTrue(guard.begin("a"))
        guard.finish("a", ok = false)
        assertFalse(guard.busy.value)
        // Schreiben gescheitert: erneut speichern geht
        assertTrue(guard.begin("a"))
        guard.finish("a", ok = true)
        assertTrue("a" in guard.saved.value)
        // Blatt nach dem Drehen noch offen: dieselben Eingaben werden nicht ein zweites Mal angelegt
        assertFalse(guard.begin("a"))
        // Ein neues Blatt speichert normal
        assertTrue(guard.begin("b"))
    }
}
