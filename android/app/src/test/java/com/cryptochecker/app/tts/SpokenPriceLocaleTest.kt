package com.cryptochecker.app.tts

import com.cryptochecker.app.util.PriceFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Vorgelesene Kurse im Dezimalzeichen der Sprache der Ansage, lateinische Ziffern. Wie iOS `SpokenPriceLocaleTests`. */
class SpokenPriceLocaleTest {

    @Test
    fun decimalSeparatorFollowsSpeechLocale() {
        assertEquals("1.50", PriceFormat.spokenPrice(1.5, Locale.ENGLISH))
        assertEquals("1,50", PriceFormat.spokenPrice(1.5, Locale.GERMAN))
        assertEquals("0,00000712", PriceFormat.spokenPrice(0.00000712, Locale.FRENCH))
        assertEquals("65000", PriceFormat.spokenPrice(65000.4, Locale.GERMAN))
    }

    @Test
    fun deviceLocaleDoesNotLeakIn() {
        val before = Locale.getDefault()
        try {
            // Arabische Ziffern bzw. Komma der Gerätesprache dürfen den Grundtext nicht verändern
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("65000", PriceFormat.spokenPrice(65000.4))
            assertEquals("1.50", PriceFormat.spokenPrice(1.5))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.50", PriceFormat.spokenPrice(1.5))
            assertEquals("1.50", PriceFormat.spokenPrice(1.5, Locale.ENGLISH))
        } finally {
            Locale.setDefault(before)
        }
    }
}
