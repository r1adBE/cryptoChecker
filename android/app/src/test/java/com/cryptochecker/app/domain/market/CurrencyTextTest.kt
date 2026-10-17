package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.util.CurrencyText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Anzeigezeichen der Kürzel (nur Android zeigt Zeichen statt Kürzel, iOS hat keine solche Tabelle). */
class CurrencyTextTest {

    @Test
    fun swissFrancKeepsItsCode() {
        // «₣» war der französische Franc
        assertEquals("CHF", CurrencyText.symbol("CHF"))
    }

    @Test
    fun obsoleteRubleCodeHasNoSign() {
        assertEquals("RUR", CurrencyText.symbol("RUR"))
        assertEquals("₽", CurrencyText.symbol("RUB"))
    }

    @Test
    fun commonSignsAndPlainCodes() {
        assertEquals("$", CurrencyText.symbol("USD"))
        assertEquals("€", CurrencyText.symbol("EUR"))
        assertEquals("BTC", CurrencyText.symbol("BTC"))
    }
}
