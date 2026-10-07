package com.cryptochecker.app.util

import java.util.Locale
import kotlin.math.abs

/**
 * Grosse Beträge kurz und in der Sprache der App: «3,45 Bio. CHF», "3.45T USD".
 * Höchstens drei signifikante Stellen.
 */
object CompactAmount {

    /** @param currency Währungscode, wird angehängt (z. B. «CHF»). */
    fun format(value: Double, currency: String, locale: Locale = Locale.getDefault()): String {
        val number = runCatching {
            val f = android.icu.text.CompactDecimalFormat.getInstance(
                locale, android.icu.text.CompactDecimalFormat.CompactStyle.SHORT
            )
            f.setSignificantDigitsUsed(true)
            f.setMinimumSignificantDigits(1)
            f.setMaximumSignificantDigits(3)
            f.format(value)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback(value)
        return "$number ${currency.uppercase(Locale.ROOT)}"
    }

    /** Ersatz ohne ICU (Englisch, K/M/B/T), z. B. in Tests. */
    internal fun fallback(value: Double): String {
        if (!value.isFinite()) return "—"
        val units = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")
        val a = abs(value)
        val (div, unit) = units.firstOrNull { a >= it.first } ?: (1.0 to "")
        return significant3(value / div) + unit
    }

    /** Drei signifikante Stellen, ohne überflüssige Nullen: 3.45, 34.5, 345. */
    private fun significant3(x: Double): String {
        val bd = java.math.BigDecimal(x).round(java.math.MathContext(3))
        return bd.stripTrailingZeros().toPlainString()
    }
}
