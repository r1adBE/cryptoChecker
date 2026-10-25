package com.cryptochecker.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exportierte Einrichten-Activities arbeiten nur mit der Id eines eigenen Widgets der passenden Art. */
class WidgetConfigureGuardTest {

    private val invalid = 0
    private val own = "com.cryptochecker.app"
    private val price = "com.cryptochecker.app.widget.PriceWidgetProvider"

    private fun accepts(id: Int, pkg: String?, cls: String?, ownPackage: String = own) =
        WidgetConfigureGuard.accepts(id, invalid, pkg, cls, ownPackage, price)

    @Test
    fun `own widget of the matching provider is accepted`() {
        assertTrue(accepts(42, own, price))
        // Testversion mit anderem Paket: Anbieter-Paket = Paket der App
        assertTrue(accepts(7, "com.cryptochecker.app.debug", price, ownPackage = "com.cryptochecker.app.debug"))
    }

    @Test
    fun `invalid or missing id is rejected`() {
        assertFalse(accepts(invalid, own, price))
    }

    @Test
    fun `unknown widget is rejected`() {
        // Systemdienst kennt die Id nicht bzw. gibt keinen Zugriff (fremdes Widget)
        assertFalse(accepts(42, null, null))
        assertFalse(accepts(42, own, null))
    }

    @Test
    fun `widget of another app is rejected`() {
        assertFalse(accepts(42, "com.example.other", price))
        assertFalse(accepts(42, "com.example.other", "com.example.other.Provider"))
    }

    @Test
    fun `own widget of another kind is rejected`() {
        assertFalse(accepts(42, own, "com.cryptochecker.app.widget.PortfolioWidgetProvider"))
    }

    @Test
    fun `empty own package never matches an unknown provider`() {
        assertFalse(accepts(42, null, price, ownPackage = ""))
    }
}
