package com.cryptochecker.app.domain.convert

import com.cryptochecker.app.domain.convert.CurrencyConversion.QuoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyConversionTest {

    /** Auszug aus FxRateSource.CURRENCIES (ohne Android-/Netz-Abhängigkeit). */
    private val fiat = listOf("CHF", "EUR", "USD", "GBP", "JPY", "KRW", "TRY", "BRL", "INR", "IDR", "AUD", "MXN")

    @Test
    fun `stablecoins und USD gelten als USD`() {
        for (code in listOf("USD", "usdt", "USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USDE", "USD1", "RLUSD", "PYUSD", "USDP", "GUSD")) {
            assertEquals(code, QuoteKind.USD, CurrencyConversion.classify(code, fiat))
        }
    }

    @Test
    fun `fiat und krypto werden unterschieden`() {
        assertEquals(QuoteKind.FIAT, CurrencyConversion.classify("eur", fiat))
        assertEquals(QuoteKind.FIAT, CurrencyConversion.classify(" KRW ", fiat))
        assertEquals(QuoteKind.CRYPTO, CurrencyConversion.classify("BTC", fiat))
        assertEquals(QuoteKind.CRYPTO, CurrencyConversion.classify("ETH", fiat))
        assertEquals(QuoteKind.CRYPTO, CurrencyConversion.classify("BNB", fiat))
        assertEquals(QuoteKind.INVALID, CurrencyConversion.classify("", fiat))
        assertEquals(QuoteKind.INVALID, CurrencyConversion.classify("BTC-PERP", fiat))
        assertEquals(QuoteKind.INVALID, CurrencyConversion.classify(null, fiat))
    }

    @Test
    fun `quote nach USD`() {
        assertEquals(1.0, CurrencyConversion.quoteToUsd(QuoteKind.USD, null, null)!!, 0.0)
        // 1 USD = 0.92 EUR → 1 EUR = 1/0.92 USD
        assertEquals(1.0 / 0.92, CurrencyConversion.quoteToUsd(QuoteKind.FIAT, 0.92, null)!!, 1e-12)
        assertEquals(60_000.0, CurrencyConversion.quoteToUsd(QuoteKind.CRYPTO, null, 60_000.0)!!, 0.0)
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.FIAT, null, null))
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.FIAT, 0.0, null))
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.CRYPTO, null, Double.NaN))
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.INVALID, 1.0, 1.0))
    }

    @Test
    fun `stablecoins nach der Portfolio-Regel`() {
        // USD und USDT immer genau 1, auch mit einem «Marktkurs»
        assertEquals(1.0, CurrencyConversion.stableToUsd("USDT", 0.98)!!, 0.0)
        assertEquals(1.0, CurrencyConversion.stableToUsd("usd", 0.98)!!, 0.0)
        // andere Stablecoins: Marktkurs, ohne Kurs KEIN Faktor (wie das Portfolio, kein stilles 1)
        assertEquals(0.9993, CurrencyConversion.stableToUsd("USDC", 0.9993)!!, 0.0)
        assertNull(CurrencyConversion.stableToUsd("USDC", null))
        assertEquals(0.97, CurrencyConversion.quoteToUsd(QuoteKind.USD, null, 0.97, "USDE")!!, 0.0)
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.USD, null, null, "DAI"))
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.USD, null, Double.NaN, "PYUSD"))
        assertNull(CurrencyConversion.quoteToUsd(QuoteKind.USD, null, 0.0, "FDUSD"))
        assertEquals(1.0, CurrencyConversion.quoteToUsd(QuoteKind.USD, null, 0.97, "USDT")!!, 0.0)
        // BTC/USDC ≈ CHF ohne USDC-Kurs: kein Faktor, also kein ≈-Wert
        assertNull(CurrencyConversion.rate(CurrencyConversion.quoteToUsd(QuoteKind.USD, null, null, "USDC"), 0.8))
        assertTrue(CurrencyConversion.stableNeedsMarket("usdc"))
        assertFalse(CurrencyConversion.stableNeedsMarket("USDT"))
        assertFalse(CurrencyConversion.stableNeedsMarket("USD"))
    }

    @Test
    fun `USDT zum Marktkurs USDT-USD`() {
        // Owner-Entscheid Okt. 2026: USDT gilt seinen Marktkurs; USD bleibt 1
        assertEquals(0.97, CurrencyConversion.stableToUsd("USDT", null, usdtUsd = 0.97)!!, 1e-12)
        assertEquals(1.0, CurrencyConversion.stableToUsd("USD", null, usdtUsd = 0.97)!!, 0.0)
        // andere Stablecoins: Marktkurs in USDT × USDT/USD
        assertEquals(0.9993 * 0.97, CurrencyConversion.stableToUsd("USDC", 0.9993, usdtUsd = 0.97)!!, 1e-12)
        // BTC/USDT in CHF: USDT/USD 0.97 senkt den Wert um 3 %
        val rate = CurrencyConversion.rate(CurrencyConversion.quoteToUsd(QuoteKind.USD, null, null, "USDT", usdtUsd = 0.97), 0.8)!!
        assertEquals(0.776, rate, 1e-12)
        // Krypto-Quote (über USDT bewertet): × USDT/USD
        assertEquals(3000.0 * 0.97, CurrencyConversion.quoteToUsd(QuoteKind.CRYPTO, null, 3000.0, "ETH", usdtUsd = 0.97)!!, 1e-9)
        // unplausibler Kurs (Börsenfehler) → 1
        assertEquals(1.0, CurrencyConversion.stableToUsd("USDT", null, usdtUsd = 7.0)!!, 0.0)
    }

    @Test
    fun `devisenkurs aelter als drei stunden`() {
        val h = 60 * 60_000L
        val now = 100 * h
        // Kein Devisenkurs beteiligt (USDT → USD, Krypto in USD): nie ein Hinweis
        assertNull(CurrencyConversion.staleFxTime(null, null, now))
        // Frisch (genau 3 h gilt noch als frisch)
        assertNull(CurrencyConversion.staleFxTime(null, now - 3 * h, now))
        assertNull(CurrencyConversion.staleFxTime(now - h, now - 2 * h, now))
        // Älter: Uhrzeit des ältesten beteiligten Kurses
        assertEquals(now - 4 * h, CurrencyConversion.staleFxTime(null, now - 4 * h, now))
        assertEquals(now - 5 * h, CurrencyConversion.staleFxTime(now - 5 * h, now - h, now))
        assertEquals(3 * h, CurrencyConversion.FX_STALE_MILLIS)
    }

    @Test
    fun `faktor quote nach ziel`() {
        // USDT → CHF bei 1 USD = 0.8 CHF
        assertEquals(0.8, CurrencyConversion.rate(1.0, 0.8)!!, 1e-12)
        // EUR → CHF: 1 USD = 0.92 EUR, 1 USD = 0.8 CHF → 1 EUR = 0.8 / 0.92 CHF
        val eurUsd = CurrencyConversion.quoteToUsd(QuoteKind.FIAT, 0.92, null)
        assertEquals(0.8 / 0.92, CurrencyConversion.rate(eurUsd, 0.8)!!, 1e-12)
        // BTC → CHF: 60'000 USDT × 0.8
        assertEquals(48_000.0, CurrencyConversion.rate(60_000.0, 0.8)!!, 1e-9)
        assertNull(CurrencyConversion.rate(null, 0.8))
        assertNull(CurrencyConversion.rate(1.0, null))
        assertNull(CurrencyConversion.rate(-1.0, 0.8))
        assertEquals(76_800.0, CurrencyConversion.convert(96_000.0, 0.8)!!, 1e-9)
        assertNull(CurrencyConversion.convert(96_000.0, null))
    }

    @Test
    fun `gleiche waehrung ohne beachtung der schreibweise`() {
        assertTrue(CurrencyConversion.sameCurrency("chf", "CHF"))
        assertFalse(CurrencyConversion.sameCurrency("USDT", "USD"))
        assertFalse(CurrencyConversion.sameCurrency("", ""))
    }

    @Test
    fun `BGN aus EUR ab der Euro-Einfuehrung`() {
        val newYear = java.time.LocalDate.of(2026, 1, 1)
        assertTrue(CurrencyConversion.deriveBgnFromEur("BGN", newYear))
        assertTrue(CurrencyConversion.deriveBgnFromEur(" bgn ", java.time.LocalDate.of(2026, 10, 5)))
        assertFalse(CurrencyConversion.deriveBgnFromEur("BGN", java.time.LocalDate.of(2025, 12, 31)))
        assertFalse(CurrencyConversion.deriveBgnFromEur("EUR", newYear))
        // 1 USD = 0.86 EUR → 1 USD = 0.86 × 1.95583 BGN
        assertEquals(0.86 * 1.95583, CurrencyConversion.bgnFromEur(0.86)!!, 1e-12)
        assertNull(CurrencyConversion.bgnFromEur(null))
        assertNull(CurrencyConversion.bgnFromEur(0.0))
        assertNull(CurrencyConversion.bgnFromEur(Double.NaN))
    }
}
