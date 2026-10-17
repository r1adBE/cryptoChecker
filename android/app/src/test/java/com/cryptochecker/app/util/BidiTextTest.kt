package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Spiegel: Tests/ThresholdParserTests.swift (`testBidiTextOnlyForRightToLeftLanguages`). */
class BidiTextTest {

    private val ar = Locale.forLanguageTag("ar")
    private val he = Locale.forLanguageTag("he")
    private val fa = Locale.forLanguageTag("fa")

    @Test
    fun `numbers become a left-to-right island only in rtl languages`() {
        assertEquals("+1.20%", BidiText.ltr("+1.20%", Locale.US))
        assertEquals("⁦+1.20%⁩", BidiText.ltr("+1.20%", ar))
        assertEquals("", BidiText.ltr("", ar))
    }

    @Test
    fun `names become an isolate only in rtl languages`() {
        assertEquals("⁨Binance⁩", BidiText.isolate("Binance", he))
        assertEquals("⁨Binance⁩", BidiText.isolate("Binance", fa))
        assertEquals("Binance", BidiText.isolate("Binance", Locale.GERMAN))
    }

    @Test
    fun `rtl detection includes old android codes`() {
        assertTrue(BidiText.isRtl(Locale.forLanguageTag("iw")))
        assertTrue(BidiText.isRtl(Locale.forLanguageTag("ar-EG")))
        assertFalse(BidiText.isRtl(Locale.ENGLISH))
    }

    @Test
    fun `strip removes direction marks`() {
        assertEquals("BTC/USDT", BidiText.strip(BidiText.isolate("BTC/USDT", ar)))
        assertTrue(BidiText.isMark('‎'))
        assertFalse(BidiText.isMark('A'))
    }
}
