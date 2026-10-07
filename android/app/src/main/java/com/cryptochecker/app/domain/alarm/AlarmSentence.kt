package com.cryptochecker.app.domain.alarm

import java.text.NumberFormat
import java.util.Locale

/**
 * «Alarm als Satz»: welche Vorlage und welche Werte. Reines Kotlin (testbar);
 * die Vorlage wählt die Oberfläche (alarm_sentence_*), siehe AlarmSentenceText.
 */
object AlarmSentence {

    /** Art des Satzes; entspricht je einer Vorlage alarm_sentence_*. */
    enum class Kind { ABOVE, BELOW, UP_PERCENT, DOWN_PERCENT, MOVE_PERCENT, VOLUME, NEAR_HIGH, NEAR_LOW }

    /**
     * Ein fertig vorbereiteter Satz: [symbol] = %1$s, [value] = %2$s,
     * [windowHours] = %3$d (nur bei [Kind.MOVE_PERCENT]); bei [Kind.NEAR_HIGH] /
     * [Kind.NEAR_LOW] der Zeitraum in Tagen (30, 90, 365 — siehe [NearExtreme.windowDays]).
     */
    data class Parts(
        val kind: Kind,
        val symbol: String,
        val value: String,
        val windowHours: Int,
    )

    /**
     * @param threshold Schwellwert (Kurs, Prozent oder Faktor); null/≤ 0 = unvollständig
     * @param currency Währung des Kurs-Schwellwerts (Alarmwährung oder Quote)
     * @param formatPrice Kurs mit Währung formatieren (in der App: PriceFormat.priceWithCurrency)
     * @return null, wenn die Eingabe leer oder ungültig ist (→ alarm_sentence_incomplete)
     */
    fun parts(
        kind: Kind,
        symbol: String,
        threshold: Double?,
        currency: String,
        windowHours: Int,
        locale: Locale = Locale.getDefault(),
        formatPrice: (Double, String) -> String,
    ): Parts? {
        val value = threshold?.takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() } ?: return null
        if (symbol.isBlank()) return null
        if (kind == Kind.MOVE_PERCENT && windowHours <= 0) return null
        val text = when (kind) {
            Kind.ABOVE, Kind.BELOW -> formatPrice(value, currency)
            Kind.UP_PERCENT, Kind.DOWN_PERCENT, Kind.MOVE_PERCENT, Kind.NEAR_HIGH, Kind.NEAR_LOW -> percent(value, locale)
            Kind.VOLUME -> factor(value)
        }
        val window = if (kind == Kind.NEAR_HIGH || kind == Kind.NEAR_LOW) NearExtreme.windowDays(windowHours) else windowHours
        return Parts(kind, symbol.trim(), text, window)
    }

    /** «5 %» bzw. «5%» je nach Sprache, höchstens zwei Nachkommastellen. */
    fun percent(value: Double, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getPercentInstance(locale)
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 2
        return format.format(value / 100.0)
    }

    /** Faktor ohne überflüssige Nachkommastellen: 3 → «3», 4.25 → «4.3» (wie AlarmTexts.factor). */
    fun factor(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", rounded)
    }
}
