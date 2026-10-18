package com.cryptochecker.app.ui.features.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sprung in den Markt-Tab: gespeichert als Name, unbekannt bzw. leer = kein Sprung. */
class MarketJumpTest {

    @Test
    fun roundTripsByName() {
        assertEquals(MarketJump.MACRO, MarketJump.fromName(MarketJump.MACRO.name))
        assertNull(MarketJump.fromName(null))
        assertNull(MarketJump.fromName("UNKNOWN"))
    }
}
