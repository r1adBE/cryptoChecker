package com.cryptochecker.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 10: «Alle an» / «Rückgängig» nur nach den Einstellungen — gleich wie iOS `PriceNotificationRuleTests`. */
class PriceNotificationRuleTest {

    private fun show(
        master: Boolean = true, pair: Boolean = true, disabled: Boolean = false, notTraded: Boolean = false, price: Double? = 100.0,
    ) = PriceNotificationRule.showNow(master, pair, disabled, notTraded, price)

    @Test
    fun `shows only with master switch, pair switch, active traded pair and a price`() {
        assertTrue(show())
        assertFalse(show(master = false))
        assertFalse(show(pair = false))
        assertFalse(show(disabled = true))
        assertFalse(show(notTraded = true))
        assertFalse(show(price = null))
        assertFalse(show(price = 0.0))
        assertFalse(show(price = Double.NaN))
    }

    @Test
    fun `reference only when shown`() {
        assertEquals(42.0, PriceNotificationRule.referenceAfterEnable(true, 42.0)!!, 0.0)
        assertNull(PriceNotificationRule.referenceAfterEnable(false, 42.0))
    }
}
