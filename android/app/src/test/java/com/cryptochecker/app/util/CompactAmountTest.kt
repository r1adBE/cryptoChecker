package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactAmountTest {

    @Test
    fun fallbackThreeSignificantDigits() {
        assertEquals("3.45T", CompactAmount.fallback(3.4512e12))
        assertEquals("120B", CompactAmount.fallback(1.2e11))
        assertEquals("98.8B", CompactAmount.fallback(9.876e10))
        assertEquals("1.5M", CompactAmount.fallback(1_500_000.0))
        assertEquals("950", CompactAmount.fallback(950.0))
        assertEquals("—", CompactAmount.fallback(Double.NaN))
    }

    @Test
    fun formatAppendsCurrencyCode() {
        // Ohne android.icu (JVM-Test) greift der Ersatz
        assertEquals("3.45T USD", CompactAmount.format(3.45e12, "usd", java.util.Locale.US))
    }

    @Test
    fun numberWithoutCurrency() {
        // Ohne android.icu (JVM-Test) greift der Ersatz: kein angehängter Code, keine Leerstelle
        assertEquals("1.23M", CompactAmount.number(1_234_567.0, java.util.Locale.US))
        assertEquals("12.3K", CompactAmount.number(12_300.0, java.util.Locale.US))
    }
}
