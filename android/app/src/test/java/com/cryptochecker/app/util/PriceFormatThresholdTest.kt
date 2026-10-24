package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [PriceFormat.threshold] mit einem Formatierer je Region (einmal angelegt) gibt Zeichen für Zeichen dasselbe wie der
 * bisherige Weg (je Aufruf ein neuer Formatierer) — auch abwechselnd über mehrere Regionen und aus mehreren Threads.
 */
class PriceFormatThresholdTest {

    private val locales = listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.JAPAN) +
        listOf("de-CH", "ar-EG", "fa-IR", "hi-IN", "ru-RU").map { Locale.forLanguageTag(it) }

    private val values = listOf(
        1.0005, 65_000.0, 0.00000712, 0.1 + 0.2, 1e-18, 123_456_789.123, 42.0, 1.0, 9.99999999, 0.000123456789012345,
    )

    /** Bisheriger Weg: je Aufruf ein neuer Formatierer. */
    private fun before(value: Double, locale: Locale): String {
        val exact = BigDecimal.valueOf(value).stripTrailingZeros()
        val format = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
            maximumFractionDigits = PriceFormat.THRESHOLD_MAX_DECIMALS
            minimumFractionDigits = 0
        }
        return format.format(exact)
    }

    @Test
    fun cachedFormatterGivesTheSameText() {
        repeat(2) {
            for (locale in locales) for (v in values) {
                assertEquals("$locale $v", before(v, locale), PriceFormat.threshold(v, locale))
            }
        }
        assertEquals("—", PriceFormat.threshold(0.0, Locale.US))
        assertEquals("—", PriceFormat.threshold(Double.NaN, Locale.US))
    }

    @Test
    fun sameTextFromSeveralThreads() {
        val pool = Executors.newFixedThreadPool(4)
        val tasks = (0 until 400).map { i ->
            pool.submit<Pair<String, String>> {
                val locale = locales[i % locales.size]
                val v = values[i % values.size] * (1 + i % 7)
                before(v, locale) to PriceFormat.threshold(v, locale)
            }
        }
        tasks.forEach {
            val (expected, got) = it.get(10, TimeUnit.SECONDS)
            assertEquals(expected, got)
        }
        pool.shutdown()
    }
}
