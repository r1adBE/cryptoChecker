package com.cryptochecker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.marketdata.util.DisplayFormat
import java.util.Locale
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kurs der Merkliste mit dem DecimalFormat des Geräts (ICU): Das Muster «@###» (höchstens vier gültige Stellen unter 10,
 * auch kleinste Kurse) gibt es nur hier, nicht in JVM-Tests. Gleiche Datei wie `PriceFormatParityTest` (JVM, ab 10) und
 * iOS `PriceFormatParityTests`: `testdata/parity/price_format.json` (als Asset des Test-APK).
 */
@RunWith(AndroidJUnit4::class)
class PriceFormatIcuTest {

    private lateinit var saved: Locale

    @Before
    fun remember() {
        saved = Locale.getDefault(Locale.Category.FORMAT)
    }

    @After
    fun restore() {
        Locale.setDefault(Locale.Category.FORMAT, saved)
        DisplayFormat.updateLocale()
    }

    /** Tausender-Apostroph der Schweiz je nach Plattformdaten U+2019 oder ASCII: ohne diesen Unterschied vergleichen. */
    private fun same(text: String): String = text.replace('’', '\'')

    @Test
    fun alleFaelleWieGemeinsameDatei() {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("price_format.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = JSONObject(text).getJSONArray("cases")
        assertTrue(cases.length() >= 60)
        var small = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("locale")
            val value = c.getDouble("value")
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(id.replace('_', '-')))
            assertEquals("$id $value", same(c.getString("expected")), same(DisplayFormat.formatDouble(value)))
            if (value < 10) small++
        }
        assertTrue(small >= 30)
    }
}
