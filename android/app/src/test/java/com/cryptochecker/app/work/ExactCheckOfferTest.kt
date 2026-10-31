package com.cryptochecker.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `saving turns exact checks off, every 15 minutes turns them on`() {
        assertFalse(ExactCheckOffer.exactChecksFor(ExactCheckOffer.Choice.SAVING))
        assertTrue(ExactCheckOffer.exactChecksFor(ExactCheckOffer.Choice.EVERY_15_MIN))
    }

    @Test
    fun `only every 15 minutes without permission opens the system setting`() {
        assertTrue(ExactCheckOffer.opensPermission(ExactCheckOffer.Kind.GRANT, ExactCheckOffer.Choice.EVERY_15_MIN))
        assertFalse(ExactCheckOffer.opensPermission(ExactCheckOffer.Kind.GRANT, ExactCheckOffer.Choice.SAVING))
        assertFalse(ExactCheckOffer.opensPermission(ExactCheckOffer.Kind.TURN_ON, ExactCheckOffer.Choice.EVERY_15_MIN))
        assertFalse(ExactCheckOffer.opensPermission(ExactCheckOffer.Kind.TURN_ON, ExactCheckOffer.Choice.SAVING))
    }
}
