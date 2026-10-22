// Entwurf eines Alarms im Editor (`AlarmDraft`): Felder, Prüfung des Schwellwerts (`ThresholdProblem`),
// Wechsel von Bedingung, Vorzeichen und Währung, Vorschläge und Übernahme aus einem `AlarmEntity`.
// Eigene Datei, weil der Entwurf reine Logik ohne Android-Bezug ist und eigene Tests hat
// (wie `AlarmDraft.swift`).
package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.settings.inputDecimalSeparator
import java.math.BigDecimal
import java.math.MathContext

/** Grund für einen ungültigen Schwellwert ([AlarmDraft.thresholdProblem]). */
enum class ThresholdProblem { EMPTY, INVALID, FUNDING_RANGE, OI_RANGE, PERCENT_RANGE }

/**
 * Fallende Prozent-Alarme («fällt um x %», «Open Interest fällt um x %»): mehr als 100 % kann nichts fallen — ein
 * solcher Alarm löste nie aus (seit Build 102 abgelehnt). Steigen und «bewegt sich um» bleiben offen. Wie iOS.
 */
const val MAX_FALL_PERCENT = 100.0

/** Fällt bei dieser Bedingung der Wert (Schwellwert höchstens [MAX_FALL_PERCENT])? */
val AlarmCondition.isFallingPercent: Boolean
    get() = this == AlarmCondition.CHANGE_PERCENT_DOWN || this == AlarmCondition.OI_DOWN

/** Eingabezustand des Alarm-Dialogs. */
data class AlarmDraft(
    val id: Long = 0,
    val condition: AlarmCondition = AlarmCondition.PRICE_ABOVE,
    val thresholdText: String = "",
    val repeating: Boolean = false,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val speak: Boolean = false,
    /** Zeitfenster für «bewegt sich um x % in y Stunden»; bei «Nahe am Hoch/Tief» Tage. */
    val windowHours: Int = 4,
    /** Währung des Schwellwerts bei Kursalarmen; null = Quote-Währung des Paars. */
    val currency: String? = null,
    /**
     * Aktueller Kurs in der Währung des Schwellwerts (nur Kursalarme): entscheidet bei
     * mehrdeutiger Eingabe wie «60,000», siehe [ThresholdParser]. null = unbekannt.
     */
    val priceHint: Double? = null,
    /** Dezimalzeichen der Region (Punkt oder Komma). */
    val decimalSeparator: Char = localeDecimalSeparator(),
    /**
     * «Nahe am Hoch/Tief»: nur neue Hochs/Tiefs melden (Abstand 0, Vorlage «Neues 30-Tage-Hoch»).
     * Das Abstandsfeld behält seinen Wert für den Fall, dass wieder ausgeschaltet wird.
     */
    val newExtremeOnly: Boolean = false,
    /**
     * Zuletzt vorgeschlagener Betrag (einfacher Modus); null = keiner. Steht er unverändert im
     * Feld, folgt er beim Wechsel «über» ↔ «unter» der neuen Richtung ([AlarmTemplates.suggestedThresholdText]).
     */
    val suggestedText: String? = null,
    /**
     * Neuer Alarm: Feld leer, der aktuelle Kurs nur als Platzhalter ([pricePlaceholder],
     * [blankFor]) — so fragt «Löst sofort aus» nur, wenn der Nutzer selbst eine überschrittene Marke
     * tippt. Fällt mit der ersten Eingabe weg.
     */
    val blankStart: Boolean = false,
) {
    /**
     * Gelesener Schwellwert (Tausendertrennung, Dezimalzeichen der Region; siehe [ThresholdParser]).
     * Funding mit Vorzeichen (auch 0), siehe [DerivativesAlarm.parseFunding].
     */
    val threshold: Double?
        get() = if (condition.isNearExtreme && newExtremeOnly) NearExtreme.NEW_ONLY_DISTANCE
        else if (condition.isFunding) DerivativesAlarm.parseFunding(thresholdText, decimalSeparator)
        else if (exceedsFall) null
        else if (condition.isOpenInterest) ThresholdParser.parse(thresholdText, decimalSeparator)
            ?.takeIf { DerivativesAlarm.isValidThreshold(condition, it) }
        else ThresholdParser.parse(
            thresholdText,
            decimalSeparator,
            priceHint.takeIf { condition.isPriceThreshold },
        )

    /** Fallender Prozent-Alarm mit gültiger Zahl über [MAX_FALL_PERCENT]? */
    private val exceedsFall: Boolean
        get() = condition.isFallingPercent &&
            (ThresholdParser.parse(thresholdText, decimalSeparator)?.let { it > MAX_FALL_PERCENT } ?: false)

    val isValid: Boolean get() = threshold != null

    /**
     * Warum der Knopf gesperrt ist (null = gültig) — für den Fehlertext unter dem Feld. Folgt genau
     * [threshold]: leer; Funding ausserhalb ±[DerivativesAlarm.MAX_FUNDING_PERCENT] bzw. keine Zahl;
     * Open Interest über [DerivativesAlarm.MAX_OI_PERCENT]; fallend über [MAX_FALL_PERCENT]; sonst keine Zahl
     * grösser als 0. Wie iOS.
     */
    val thresholdProblem: ThresholdProblem?
        get() = when {
            isValid -> null
            thresholdText.isBlank() -> ThresholdProblem.EMPTY
            condition.isFunding -> ThresholdProblem.FUNDING_RANGE
            exceedsFall -> ThresholdProblem.PERCENT_RANGE
            condition.isOpenInterest && ThresholdParser.parse(thresholdText, decimalSeparator) != null ->
                ThresholdProblem.OI_RANGE
            else -> ThresholdProblem.INVALID
        }

    /**
     * Bedingung wechseln. Beim Volumen-Spike ist der Wert ein Faktor (×2…×10),
     * deshalb beim Wechsel von/zu dieser Bedingung den Wert neu setzen.
     */
    fun withCondition(newCondition: AlarmCondition): AlarmDraft {
        if (newCondition == condition) return this
        val switched = switchCondition(newCondition)
        // Zwischen Kursmarke und Prozent wechseln: ein Kurs ist kein Prozentwert (und umgekehrt).
        // Zurück zur Kursmarke wieder mit dem aktuellen Kurs als Vorschlag (wie beim Anlegen).
        return when {
            // «über» ↔ «unter»: unveränderter Vorschlag folgt der Richtung (5 % über bzw. unter dem Kurs)
            condition.isPriceThreshold && newCondition.isPriceThreshold ->
                if (isUntouchedSuggestion && priceHint != null) switched.suggested(newCondition) else switched
            condition.isPriceThreshold == newCondition.isPriceThreshold -> switched
            newCondition.isPriceThreshold -> switched.suggested(newCondition)
            newCondition.isPercent -> switched.copy(thresholdText = "")
            else -> switched
        }
    }

    /** Steht noch der Vorschlag im Feld (nicht angetippt)? */
    /**
     * Fehlertext, der unter dem Feld steht: wie [thresholdProblem], nur «leer» nicht, solange im
     * neuen Alarm noch nichts getippt wurde ([blankStart]) — er beginnt leer, ohne gleich rot zu
     * sein. Der Knopf bleibt trotzdem gesperrt. Wie iOS.
     */
    val visibleProblem: ThresholdProblem?
        get() = thresholdProblem.takeUnless { it == ThresholdProblem.EMPTY && blankStart }

    /**
     * Hinweis «Betrag eingeben» unter dem gesperrten Knopf: nur solange das Feld eines neuen Alarms noch leer ist
     * (der graue Platzhalter wirkt sonst wie ein Wert, und ohne Fehlertext bliebe offen, warum der Knopf gesperrt ist).
     * Wie iOS.
     */
    val showsEnterAmountHint: Boolean
        get() = thresholdProblem == ThresholdProblem.EMPTY && visibleProblem == null

    /**
     * Satzkarte («Sag mir Bescheid, wenn …») zeigen? Nicht, solange der Hinweis «Wert eingeben» unter dem Knopf steht
     * ([showsEnterAmountHint]) — sonst sagten Karte und Hinweis bei leerem Feld zweimal dasselbe. Wie iOS.
     */
    val showsSentence: Boolean
        get() = !showsEnterAmountHint

    /**
     * Platzhalter im leeren Betragsfeld eines neuen Alarms: der aktuelle Kurs in der Währung des
     * Schwellwerts ([priceHint]), sechs gültige Stellen, Format wie die Eingabe. null = keiner.
     */
    val pricePlaceholder: String?
        get() {
            if (!blankStart || thresholdText.isNotEmpty()) return null
            val price = priceHint?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return BigDecimal.valueOf(price).round(MathContext(PLACEHOLDER_DIGITS)).stripTrailingZeros().toPlainString()
                .replace('.', ThresholdParser.normalized(decimalSeparator))
        }

    val isUntouchedSuggestion: Boolean
        get() = !suggestedText.isNullOrEmpty() && thresholdText == suggestedText

    /** Vorschlag für [forCondition] aus dem aktuellen Kurs in der Währung des Schwellwerts ([priceHint]). */
    private fun suggested(forCondition: AlarmCondition): AlarmDraft {
        val text = AlarmTemplates.suggestedThresholdText(priceHint, forCondition, decimalSeparator)
        return copy(thresholdText = text, suggestedText = text)
    }

    private fun switchCondition(newCondition: AlarmCondition): AlarmDraft {
        return when {
            // Funding: Schwelle in % mit Vorzeichen (Vorschlag 0,05 %)
            newCondition.isFunding -> copy(
                condition = newCondition,
                thresholdText = if (condition.isFunding) thresholdText
                else formatThreshold(DerivativesAlarm.DEFAULT_FUNDING_PERCENT, decimalSeparator),
                windowHours = if (condition.isNearExtreme) DEFAULT_WINDOW_HOURS else windowHours,
            )
            // Open Interest: Veränderung in % (Vorschlag 10 %), Fenster 1, 4 oder 24 Stunden
            newCondition.isOpenInterest -> copy(
                condition = newCondition,
                thresholdText = if (condition.isOpenInterest) thresholdText
                else formatThreshold(DerivativesAlarm.DEFAULT_OI_PERCENT, decimalSeparator),
                windowHours = DerivativesAlarm.oiWindowHours(
                    if (condition.isNearExtreme) DerivativesAlarm.DEFAULT_OI_WINDOW_HOURS else windowHours
                ),
            )
            newCondition == AlarmCondition.VOLUME_SPIKE ->
                copy(condition = newCondition, thresholdText = formatFactor(DEFAULT_VOLUME_FACTOR))
            // Nahe am Hoch/Tief: Abstand in % (Standard 2 %), Zeitraum in Tagen (Standard 30)
            newCondition.isNearExtreme -> copy(
                condition = newCondition,
                thresholdText = if (condition.isPercent || condition.isNearExtreme) thresholdText
                else formatFactor(NearExtreme.DEFAULT_DISTANCE_PERCENT),
                windowHours = if (condition.isNearExtreme) windowHours else NearExtreme.DEFAULT_WINDOW_DAYS,
            )
            condition.isNearExtreme -> copy(
                condition = newCondition,
                thresholdText = if (newCondition.isPercent) thresholdText else "",
                windowHours = DEFAULT_WINDOW_HOURS,
            )
            condition == AlarmCondition.VOLUME_SPIKE -> copy(condition = newCondition, thresholdText = "")
            // Funding/Open Interest zurück auf eine andere Bedingung: deren Wert passt nicht
            condition.isDerivatives -> copy(condition = newCondition, thresholdText = "")
            else -> copy(condition = newCondition)
        }
    }

    /** Funding: Vorzeichen der Eingabe wechseln («0,01» ↔ «-0,01»), da Zifferntastaturen oft kein Minus haben. */
    fun withToggledSign(): AlarmDraft {
        val text = thresholdText.trim()
        val flipped = when {
            text.startsWith('-') || text.startsWith('\u2212') -> text.substring(1).trimStart()
            text.startsWith('+') -> "-" + text.substring(1).trimStart()
            else -> "-$text"
        }
        return copy(thresholdText = flipped)
    }

    /**
     * Währung des Schwellwerts wechseln (null = Quote). Ist der Faktor Quote →
     * [other] bekannt, wird ein bereits getippter Wert mit umgerechnet.
     * @param other die Nicht-Quote-Währung der Auswahl (z. B. «CHF»)
     * @param rate Faktor Quote → [other]
     */
    fun withCurrency(newCurrency: String?, other: String, rate: Double?): AlarmDraft {
        if (newCurrency == currency) return this
        val value = threshold
        val converted = when {
            value == null || rate == null || rate <= 0.0 -> null
            currency == null && newCurrency == other -> value * rate
            currency == other && newCurrency == null -> value / rate
            else -> null
        }
        return copy(
            currency = newCurrency,
            thresholdText = converted?.let { formatConverted(it, decimalSeparator) } ?: thresholdText,
        )
    }

    companion object {
        /**
         * Umgerechneten Wert fürs Eingabefeld runden: sechs gültige Stellen, ohne Exponent und
         * Tausendertrennung, mit dem Dezimalzeichen der Region (liest sich so eindeutig zurück).
         */
        fun formatConverted(value: Double, decimalSeparator: Char = '.'): String =
            BigDecimal.valueOf(value).round(MathContext(6)).stripTrailingZeros().toPlainString()
                .replace('.', ThresholdParser.normalized(decimalSeparator))

        /**
         * Dezimalzeichen der Region (Sprache der App, Region des Geräts — wie iOS), auf Punkt
         * oder Komma abgebildet ([inputDecimalSeparator]).
         */
        fun localeDecimalSeparator(): Char = inputDecimalSeparator()

        /** Schwellwert fürs Eingabefeld: ohne Exponent und Tausendertrennung, Dezimalzeichen der Region. */
        fun formatThreshold(value: Double, decimalSeparator: Char = localeDecimalSeparator()): String =
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
                .replace('.', ThresholdParser.normalized(decimalSeparator))

        /**
         * Kurs, an dem eine mehrdeutige Eingabe gemessen wird: [lastPrice] in der Währung des
         * Schwellwerts ([currency] = null → Quote; sonst mit dem Faktor aus [rates]).
         */
        fun priceHint(lastPrice: Double?, currency: String?, rates: Map<String, Double>): Double? {
            val price = lastPrice?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            if (currency == null) return price
            val rate = rates[currency.uppercase()]?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return price * rate
        }

        /** Wählbare Faktoren für den Volumen-Spike. */
        val VOLUME_FACTORS = listOf(2.0, 3.0, 5.0, 10.0)
        const val DEFAULT_VOLUME_FACTOR = 3.0

        /** Standard-Zeitfenster für «bewegt sich um x % in y Stunden». */
        const val DEFAULT_WINDOW_HOURS = 4

        fun formatFactor(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        /**
         * Neuer Alarm im einfachen Modus: «Wenn BTC über [Betrag] geht», Betrag = 5 % über dem
         * aktuellen Kurs, auf drei gültige Stellen ([AlarmTemplates.suggestedThresholdText]).
         */
        /**
         * Neuer Alarm mit leerem Feld; der aktuelle Kurs steht als Platzhalter ([pricePlaceholder],
         * aus [priceHint]). «über» wie bei [newFor].
         */
        fun blankFor(decimalSeparator: Char = localeDecimalSeparator()): AlarmDraft =
            AlarmDraft(decimalSeparator = decimalSeparator, blankStart = true)

        /** Gültige Stellen des Kurses als Platzhalter ([pricePlaceholder]). */
        const val PLACEHOLDER_DIGITS = 6

        fun newFor(lastPrice: Double?, decimalSeparator: Char = localeDecimalSeparator()): AlarmDraft {
            val text = AlarmTemplates.suggestedThresholdText(lastPrice, AlarmCondition.PRICE_ABOVE, decimalSeparator)
            return AlarmDraft(thresholdText = text, decimalSeparator = decimalSeparator, suggestedText = text)
        }

        fun from(alarm: AlarmEntity): AlarmDraft = AlarmDraft(
            id = alarm.id,
            condition = alarm.condition,
            // Nur neue Hochs/Tiefs: Abstand 0 → Schalter an, Feld mit dem Standardabstand
            newExtremeOnly = alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold),
            thresholdText = if (alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold))
                formatFactor(NearExtreme.DEFAULT_DISTANCE_PERCENT)
            else formatThreshold(alarm.threshold),
            repeating = alarm.repeating,
            sound = alarm.sound,
            vibrate = alarm.vibrate,
            speak = alarm.speak,
            windowHours = when {
                alarm.condition.isNearExtreme -> NearExtreme.windowDays(alarm.windowHours)
                alarm.condition.isOpenInterest -> DerivativesAlarm.oiWindowHours(alarm.windowHours)
                else -> alarm.windowHours
            },
            currency = alarm.currency,
        )
    }
}
