package com.cryptochecker.app.domain.alarm

import java.text.NumberFormat
import com.cryptochecker.app.util.LocaleNumbers
import java.util.Locale

/**
 * «Alarm als Satz»: welche Vorlage und welche Werte. Reines Kotlin (testbar);
 * die Vorlage wählt die Oberfläche (alarm_sentence_*), siehe AlarmSentenceText.
 */
object AlarmSentence {

    /** Art des Satzes; entspricht je einer Vorlage alarm_sentence_*. */
    enum class Kind {
        ABOVE, BELOW, UP_PERCENT, DOWN_PERCENT, MOVE_PERCENT, VOLUME, NEAR_HIGH, NEAR_LOW, NEW_HIGH, NEW_LOW,
        FUNDING_ABOVE, FUNDING_BELOW, OI_UP, OI_DOWN,
    }

    /**
     * Ein fertig vorbereiteter Satz: [symbol] = %1$s, [value] = %2$s,
     * [windowHours] = %3$d (nur bei [Kind.MOVE_PERCENT], [Kind.OI_UP], [Kind.OI_DOWN]); bei [Kind.NEAR_HIGH] /
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
     * @return null, wenn die Eingabe leer oder ungültig ist (→ alarm_sentence_incomplete).
     *   [Kind.NEAR_HIGH]/[Kind.NEAR_LOW] mit Abstand 0 ([NearExtreme.NEW_ONLY_DISTANCE]) →
     *   [Kind.NEW_HIGH]/[Kind.NEW_LOW] ohne Wert («… ein neues 30-Tage-Hoch erreicht»).
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
        if ((kind == Kind.NEAR_HIGH || kind == Kind.NEAR_LOW) && threshold != null && NearExtreme.isNewOnly(threshold)) {
            if (symbol.isBlank()) return null
            val newKind = if (kind == Kind.NEAR_HIGH) Kind.NEW_HIGH else Kind.NEW_LOW
            return Parts(newKind, symbol.trim(), "", NearExtreme.windowDays(windowHours))
        }
        // Funding: mit Vorzeichen, auch 0 («unter 0 %» = Funding wird negativ)
        if (kind == Kind.FUNDING_ABOVE || kind == Kind.FUNDING_BELOW) {
            val funding = threshold?.takeIf { it.isFinite() && kotlin.math.abs(it) <= DerivativesAlarm.MAX_FUNDING_PERCENT }
                ?: return null
            if (symbol.isBlank()) return null
            return Parts(kind, symbol.trim(), fundingPercent(funding, locale), windowHours)
        }
        val value = threshold?.takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() } ?: return null
        if (symbol.isBlank()) return null
        if (kind == Kind.MOVE_PERCENT && windowHours <= 0) return null
        val text = when (kind) {
            Kind.ABOVE, Kind.BELOW -> formatPrice(value, currency)
            Kind.UP_PERCENT, Kind.DOWN_PERCENT, Kind.MOVE_PERCENT, Kind.NEAR_HIGH, Kind.NEAR_LOW,
            Kind.OI_UP, Kind.OI_DOWN -> percent(value, locale)
            Kind.VOLUME -> factor(value, locale)
            Kind.NEW_HIGH, Kind.NEW_LOW, Kind.FUNDING_ABOVE, Kind.FUNDING_BELOW -> ""
        }
        val window = when (kind) {
            Kind.NEAR_HIGH, Kind.NEAR_LOW, Kind.NEW_HIGH, Kind.NEW_LOW -> NearExtreme.windowDays(windowHours)
            Kind.OI_UP, Kind.OI_DOWN -> DerivativesAlarm.oiWindowHours(windowHours)
            else -> windowHours
        }
        return Parts(kind, symbol.trim(), text, window)
    }

    /** «5 %» bzw. «5%» je nach Sprache, höchstens zwei Nachkommastellen. */
    fun percent(value: Double, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getPercentInstance(locale)
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 2
        return format.format(value / 100.0)
    }

    /**
     * Funding Rate: «0,05 %», «−0,0125 %» — bis vier Nachkommastellen (Funding ist klein),
     * Vorzeichen wie die Sprache es schreibt.
     */
    fun fundingPercent(value: Double, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getPercentInstance(locale)
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 4
        // −0 nicht als «-0 %» zeigen
        return format.format(if (value == 0.0) 0.0 else value / 100.0)
    }

    /** Veränderung mit Vorzeichen und einer Nachkommastelle: «+12,3 %», «-4 %». */
    fun signedPercent(value: Double, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getPercentInstance(locale)
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 1
        val text = format.format(value / 100.0)
        return if (value > 0.0 && !text.startsWith("+")) "+$text" else text
    }

    /**
     * Faktor ohne überflüssige Nachkommastellen: 3 → «3», 4.25 → «4.3» (wie AlarmTexts.factor),
     * in den Ziffern von [locale].
     */
    fun factor(value: Double, locale: Locale = Locale.getDefault()): String =
        LocaleNumbers.decimal(value, 1, minDecimals = 0, locale = locale)
}
