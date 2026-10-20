package com.cryptochecker.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Widgets wie die App: Systemschrift für Beträge nur in Sprachen mit eigenen Ziffern (ar, fa). */
class WidgetAmountFontTest {

    @Test
    fun `Arabisch und Persisch nehmen die Systemschrift`() {
        assertTrue(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("ar-EG-u-nu-arab")))
        assertTrue(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("fa-IR-u-nu-arabext")))
    }

    @Test
    fun `lateinische Ziffern behalten Rubik`() {
        assertFalse(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("de-CH")))
        assertFalse(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("he-IL")))
        assertFalse(WidgetAmountFont.usesSystemFont(Locale.forLanguageTag("ar-u-nu-latn")))
    }

    @Test
    fun `ohne Systemschrift bleibt der Text unveraendert`() {
        val text = "61’234.50 USDT"
        assertTrue(WidgetAmountFont.apply(text, systemFont = false) === text)
    }
}
