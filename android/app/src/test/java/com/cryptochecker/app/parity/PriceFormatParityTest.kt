package com.cryptochecker.app.parity

import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.util.DisplayFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Kurs der Merkliste (`testdata/parity/price_format.json`, [DisplayFormat.formatDouble] hinter [PriceFormat.price]): ab 10
 * immer zwei Nachkommastellen mit der Tausendertrennung der Region. Die Fälle unter 10 (vier gültige Stellen, Muster
 * «@###») kennt nur das DecimalFormat von Android (ICU), nicht das der JVM — sie prüft `PriceFormatIcuTest` auf dem Gerät
 * mit derselben Datei. Gegenstück: `Tests/PriceFormatParityTests.swift` (alle Fälle).
 */
class PriceFormatParityTest {

    private val fixture = ParityJson.load("price_format.json")
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

    @Test
    fun abZehnWieGemeinsameFaelle() {
        val below = fixture["smallBelow"].num()!!
        val cases = fixture["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 60)
        val large = cases.filter { it["value"].num()!! >= below }
        assertTrue(large.size >= 30)
        for (c in large) {
            val id = c["locale"] as String
            val value = c["value"].num()!!
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(id.replace('_', '-')))
            assertEquals("$id $value", c["expected"], DisplayFormat.formatDouble(value))
            assertEquals("$id $value", c["expected"], PriceFormat.price(value))
        }
        // Die Fälle unter 10 gibt es in jeder Region (geprüft auf dem Gerät und unter iOS)
        assertEquals(setOf("en_US", "de_CH", "de_DE"), cases.filter { it["value"].num()!! < below }.map { it["locale"] }.toSet())
    }

    /** Die Grenze der Datei ist die des Codes: unter 10 das Muster mit gültigen Stellen, ab 10 zwei Nachkommastellen. */
    @Test
    fun grenzeWieImCode() {
        assertEquals(10.0, fixture["smallBelow"].num()!!, 0.0)
        val source = sequenceOf("", "../", "android/")
            .map { java.io.File(it + "marketdata/src/main/java/com/cryptochecker/marketdata/util/DisplayFormat.kt") }
            .first { it.isFile }.readText()
        assertTrue(source.contains("render(if (value < 10) patterns.fourDigits else patterns.cents, value)"))
        assertTrue(source.contains("""val cents = DecimalFormat("#,###.00", symbols)"""))
        assertTrue(source.contains("""val fourDigits = DecimalFormat("@###", symbols)"""))
    }
}
