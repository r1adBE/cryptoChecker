package com.cryptochecker.marketdata.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Kurs-Anzeige ab 10 ([FormatUtilsBase.formatDouble]): zwei Nachkommastellen mit Tausendertrennung der Sprache. Die
 * vier signifikanten Stellen unter 10 («@###») kennt nur das DecimalFormat von Android (ICU), nicht das der JVM, auf
 * der die Unit-Tests laufen — sie sind hier nicht geprüft.
 */
class FormatUtilsBaseTest {

    private lateinit var saved: Locale

    @Before
    fun remember() {
        saved = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.Category.FORMAT, Locale.US)
    }

    @After
    fun restore() {
        Locale.setDefault(Locale.Category.FORMAT, saved)
        FormatUtilsBase.updateLocale()
    }

    @Test
    fun abZehnImmerZweiNachkommastellen() {
        assertEquals("83,142.00", FormatUtilsBase.formatDouble(83142.0))
        assertEquals("10.00", FormatUtilsBase.formatDouble(10.0))
        assertEquals("1,234,567.89", FormatUtilsBase.formatDouble(1234567.891))
    }

    @Test
    fun spracheWechseltOhneNeustart() {
        assertEquals("83,142.00", FormatUtilsBase.formatDouble(83142.0))
        Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY)
        assertEquals("83.142,00", FormatUtilsBase.formatDouble(83142.0))
    }
}
