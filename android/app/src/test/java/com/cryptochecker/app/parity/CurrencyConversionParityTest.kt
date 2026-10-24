package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.convert.CurrencyConversion.QuoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Reine Regeln der «≈ Umrechnung» als gemeinsame Testfälle (`testdata/parity/currency_conversion.json`, Kopie in iOS
 * `Tests/Parity/`, gelesen von `CurrencyConversionParityTests.swift`): Einordnung der Quote, Quote → USD über
 * USDT-Kurse, Kette Quote → Ziel samt Betrag (sehr klein/gross, Überlauf), fehlende und veraltete Kurse an der Grenze.
 */
class CurrencyConversionParityTest {

    private val data = ParityJson.load("currency_conversion.json")
    private val fiat = data["fiat"].list().map { it as String }

    private fun assertClose(name: String, expected: Double?, actual: Double?) {
        if (expected == null) {
            assertNull(name, actual)
            return
        }
        assertNotNull(name, actual)
        assertTrue("$name: expected $expected, got $actual", abs(expected - actual!!) <= abs(expected) * 1e-12)
    }

    @Test
    fun classify() {
        data["classify"].list().also { assertTrue("classify: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val kind = CurrencyConversion.classify(c["quote"] as String, fiat)
            assertEquals("classify '${c["quote"]}'", c["kind"], kind.name)
        }
    }

    @Test
    fun quoteToUsd() {
        data["quoteToUsd"].list().also { assertTrue("quoteToUsd: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val actual = CurrencyConversion.quoteToUsd(
                QuoteKind.valueOf(c["kind"] as String),
                c["usdToQuote"].num(),
                c["cryptoUsdt"].num(),
                c["code"] as String?,
                c["usdtUsd"].num(),
            )
            assertClose(c["name"] as String, c["expected"].num(), actual)
        }
    }

    @Test
    fun chainQuoteToTargetAndAmount() {
        data["chain"].list().also { assertTrue("chain: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val quote = c["quote"] as String
            val quoteUsd = CurrencyConversion.quoteToUsd(
                CurrencyConversion.classify(quote, fiat),
                c["usdToQuote"].num(),
                c["cryptoUsdt"].num(),
                quote,
                c["usdtUsd"].num(),
            )
            val rate = CurrencyConversion.rate(quoteUsd, c["usdToTarget"].num())
            assertClose("$name (rate)", c["rate"].num(), rate)
            assertClose("$name (amount)", c["converted"].num(), CurrencyConversion.convert(c["amount"].num()!!, rate))
        }
    }

    @Test
    fun freshQuotePriceAndStaleFx() {
        data["freshQuotePrice"].list().also { assertTrue("freshQuotePrice: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val actual = CurrencyConversion.freshQuotePrice(
                c["price"].num(),
                c["time"].num()?.toLong(),
                c["now"].num()!!.toLong(),
            )
            assertEquals(c["name"] as String, c["expected"].num(), actual)
        }
        data["staleFxTime"].list().also { assertTrue("staleFxTime: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val actual = CurrencyConversion.staleFxTime(
                c["quoteFxAt"].num()?.toLong(),
                c["targetFxAt"].num()?.toLong(),
                c["now"].num()!!.toLong(),
            )
            assertEquals(c["name"] as String, c["expected"].num()?.toLong(), actual)
        }
    }

    @Test
    fun conversionQuoteAndSameCurrency() {
        data["conversionQuote"].list().also { assertTrue("conversionQuote: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val actual = CurrencyConversion.conversionQuote(c["market"] as String?, c["quote"] as String)
            assertEquals("conversionQuote ${c["market"]} ${c["quote"]}", c["expected"], actual)
        }
        data["sameCurrency"].list().also { assertTrue("sameCurrency: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val actual = CurrencyConversion.sameCurrency(c["a"] as String?, c["b"] as String?)
            assertEquals("sameCurrency ${c["a"]} ${c["b"]}", c["expected"], actual)
        }
    }
}
