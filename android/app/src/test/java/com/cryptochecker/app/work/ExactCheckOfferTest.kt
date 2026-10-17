package com.cryptochecker.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExactCheckOfferTest {

    @Test
    fun `offers once when off or permission missing`() {
        assertEquals(ExactCheckOffer.Kind.TURN_ON, ExactCheckOffer.decide(exactOn = false, permitted = true, offeredBefore = false))
        assertEquals(ExactCheckOffer.Kind.GRANT, ExactCheckOffer.decide(exactOn = false, permitted = false, offeredBefore = false))
        // An, aber Berechtigung entzogen: zur Berechtigung führen
        assertEquals(ExactCheckOffer.Kind.GRANT, ExactCheckOffer.decide(exactOn = true, permitted = false, offeredBefore = false))
    }

    @Test
    fun `never when active or already offered`() {
        assertNull(ExactCheckOffer.decide(exactOn = true, permitted = true, offeredBefore = false))
        assertNull(ExactCheckOffer.decide(exactOn = false, permitted = false, offeredBefore = true))
        assertNull(ExactCheckOffer.decide(exactOn = false, permitted = true, offeredBefore = true))
    }
}
