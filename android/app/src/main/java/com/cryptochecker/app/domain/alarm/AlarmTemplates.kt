package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import java.math.BigDecimal
import java.math.MathContext

/**
 * «Schnell-Alarme» im Alarm-Editor und der einfache Modus («Wenn BTC über 60’000 geht»).
 * Reines Kotlin, als Unit-Test prüfbar (Spiegel: Shared/Services/AlarmTemplates.swift).
 *
 * Ein Antippen legt den Alarm sofort an:
 *  - ±1 % / ±5 %: Prozentalarm ab dem Kurs von jetzt (CHANGE_PERCENT_UP/DOWN mit
 *    `referencePrice` = aktueller Kurs) — «bewegt sich um x % ab jetzt», einmalig.
 *  - Neues 30-Tage-Hoch/-Tief: «Nahe am Hoch/Tief» mit Abstand 0 = nur neue Hochs/Tiefs
 *    ([NearExtreme.NEW_ONLY_DISTANCE]), Zeitraum 30 Tage.
 *  - Volumen ×3: Volumen-Spike mit Faktor 3.
 */
object AlarmTemplates {

    /** Die Vorlagen in der Reihenfolge der Chips. */
    enum class Template {
        UP_1, UP_5, DOWN_1, DOWN_5, NEW_HIGH_30, NEW_LOW_30, VOLUME_X3;

        /** Vorzeichenbehafteter Prozentwert (±1, ±5) bei Prozent-Vorlagen, sonst null. */
        val percent: Double?
            get() = when (this) {
                UP_1 -> 1.0
                UP_5 -> 5.0
                DOWN_1 -> -1.0
                DOWN_5 -> -5.0
                else -> null
            }

        /** Braucht Tageskerzen (Hoch/Tief der letzten 30 Tage). */
        val needsDailyRange: Boolean get() = this == NEW_HIGH_30 || this == NEW_LOW_30

        /** Braucht Stundenkerzen mit Volumen. */
        val needsHourlyVolume: Boolean get() = this == VOLUME_X3
    }

    /** Zeitraum der Vorlagen «Neues 30-Tage-Hoch/-Tief» in Tagen. */
    const val NEW_EXTREME_WINDOW_DAYS = 30

    /** Faktor der Vorlage «Volumen ×3». */
    const val VOLUME_FACTOR = 3.0

    /** Was ein Antippen speichert (übrige Felder wie beim normalen Anlegen). */
    data class Definition(
        val condition: AlarmCondition,
        val threshold: Double,
        /** Bei NEAR_HIGH/NEAR_LOW der Zeitraum in Tagen, sonst 1 (unbenutzt). */
        val windowHours: Int,
        /** Bezugskurs der Prozentalarme (= Kurs beim Anlegen), sonst null. */
        val referencePrice: Double?,
        val repeating: Boolean = false,
    )

    /**
     * Sichtbare Vorlagen: Prozent-Vorlagen brauchen einen Kurs, Hoch/Tief Tageskerzen,
     * Volumen Stundenkerzen (z. B. bei DEX-Paaren ohne Kerzenquelle ausgeblendet).
     */
    fun available(hasPrice: Boolean, hasDailyRange: Boolean, hasHourlyVolume: Boolean): List<Template> =
        Template.entries.filter { t ->
            when {
                t.percent != null -> hasPrice
                t.needsDailyRange -> hasDailyRange
                t.needsHourlyVolume -> hasHourlyVolume
                else -> false
            }
        }

    /** Alarm zur Vorlage; null, wenn eine Prozent-Vorlage keinen gültigen Kurs hat. */
    fun definition(template: Template, currentPrice: Double?): Definition? {
        val percent = template.percent
        if (percent != null) {
            val price = currentPrice?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return Definition(
                condition = if (percent > 0) AlarmCondition.CHANGE_PERCENT_UP else AlarmCondition.CHANGE_PERCENT_DOWN,
                threshold = kotlin.math.abs(percent),
                windowHours = 1,
                referencePrice = price,
            )
        }
        return when (template) {
            Template.NEW_HIGH_30 -> Definition(AlarmCondition.NEAR_HIGH, NearExtreme.NEW_ONLY_DISTANCE, NEW_EXTREME_WINDOW_DAYS, null)
            Template.NEW_LOW_30 -> Definition(AlarmCondition.NEAR_LOW, NearExtreme.NEW_ONLY_DISTANCE, NEW_EXTREME_WINDOW_DAYS, null)
            Template.VOLUME_X3 -> Definition(AlarmCondition.VOLUME_SPIKE, VOLUME_FACTOR, 1, null)
            else -> null
        }
    }

    /**
     * Vorschlag fürs Betragsfeld im einfachen Modus: aktueller Kurs auf drei gültige
     * Stellen gerundet (63’412.57 → 63400, 1.2345 → 1.23, 0.00012345 → 0.000123),
     * ohne Exponent und Tausendertrennung, mit dem Dezimalzeichen der Region — liest
     * sich über [ThresholdParser] eindeutig zurück. Leer ohne gültigen Kurs.
     */
    fun suggestedThresholdText(price: Double?, decimalSeparator: Char = '.'): String {
        val value = price?.takeIf { it.isFinite() && it > 0.0 } ?: return ""
        return BigDecimal.valueOf(value).round(MathContext(SUGGESTED_DIGITS)).stripTrailingZeros().toPlainString()
            .replace('.', ThresholdParser.normalized(decimalSeparator))
    }

    private const val SUGGESTED_DIGITS = 3

    /**
     * Öffnet der Editor gleich mit «Erweitert»? Ja bei allem, was der einfache Satz nicht
     * zeigt: andere Bedingungen als Kursmarken oder ein Schwellwert in eigener Währung.
     */
    fun opensAdvanced(condition: AlarmCondition, currency: String?): Boolean =
        !condition.isPriceThreshold || !currency.isNullOrBlank()
}
