package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.AlarmSentence
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat

/** Beschreibt eine Alarmbedingung als Text – für Liste und Benachrichtigung. */
object AlarmTexts {

    fun conditionName(context: Context, condition: AlarmCondition): String =
        context.getString(
            when (condition) {
                AlarmCondition.PRICE_ABOVE -> R.string.alarm_condition_above
                AlarmCondition.PRICE_BELOW -> R.string.alarm_condition_below
                AlarmCondition.CHANGE_PERCENT_UP -> R.string.alarm_condition_up_percent
                AlarmCondition.CHANGE_PERCENT_DOWN -> R.string.alarm_condition_down_percent
                AlarmCondition.MOVE_PERCENT_WINDOW -> R.string.alarm_condition_move_percent
                AlarmCondition.VOLUME_SPIKE -> R.string.alarm_condition_volume_spike
                AlarmCondition.NEAR_HIGH -> R.string.alarm_condition_near_high
                AlarmCondition.NEAR_LOW -> R.string.alarm_condition_near_low
                AlarmCondition.FUNDING_ABOVE -> R.string.alarm_condition_funding_above
                AlarmCondition.FUNDING_BELOW -> R.string.alarm_condition_funding_below
                AlarmCondition.OI_UP -> R.string.alarm_condition_oi_up
                AlarmCondition.OI_DOWN -> R.string.alarm_condition_oi_down
            }
        )

    fun describe(context: Context, alarm: AlarmEntity): String {
        val name = conditionName(context, alarm.condition)
        // «Volumen-Spike ×3»
        if (alarm.condition == AlarmCondition.VOLUME_SPIKE) {
            return "$name ×${factor(alarm.threshold)}"
        }
        // «Funding über 0,05 %»
        if (alarm.condition.isFunding) {
            return "$name " + AlarmSentence.fundingPercent(alarm.threshold, locale(context))
        }
        // «Open Interest steigt 10 % in 4 Std.»
        if (alarm.condition.isOpenInterest) {
            val hours = DerivativesAlarm.oiWindowHours(alarm.windowHours)
            return "$name " + AlarmSentence.percent(alarm.threshold, locale(context)) + " " +
                context.resources.getQuantityString(R.plurals.alarm_window_hours, hours, hours)
        }
        // Nur neue Hochs/Tiefs (Abstand 0): «Neues 30-Tage-Hoch»
        if (alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold)) {
            return newExtremeLabel(context, alarm.condition == AlarmCondition.NEAR_HIGH, alarm.windowHours)
        }
        // «Nahe am Hoch 2.00% · 30 Tage»
        if (alarm.condition.isNearExtreme) {
            return "$name " + "%.2f%%".format(alarm.threshold) + " · " +
                windowLabel(context, NearExtreme.windowDays(alarm.windowHours))
        }
        val value = if (alarm.condition.isPercent) {
            "%.2f%%".format(alarm.threshold)
        } else {
            // Schwellwert in eigener Währung: «Über 60’000 CHF»
            alarm.convertCurrency?.let { PriceFormat.priceWithCurrency(alarm.threshold, it) }
                ?: PriceFormat.price(alarm.threshold)
        }
        if (alarm.condition == AlarmCondition.MOVE_PERCENT_WINDOW) {
            return "$name $value " + context.resources.getQuantityString(R.plurals.alarm_window_hours, alarm.windowHours, alarm.windowHours)
        }
        return "$name $value"
    }

    /**
     * Gemessener Wert in der Benachrichtigung eines Funding- bzw. Open-Interest-Alarms:
     * Funding «0,061 %», Open-Interest-Veränderung «+12,3 %».
     */
    fun derivativesValue(context: Context, condition: AlarmCondition, value: Double): String =
        if (condition.isFunding) AlarmSentence.fundingPercent(value, locale(context))
        else AlarmSentence.signedPercent(value, locale(context))

    /** Sprache der App (kann von der Systemsprache abweichen). */
    private fun locale(context: Context): java.util.Locale =
        context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()

    /** «30 Tage», «90 Tage», «1 Jahr» — Zeitraum des Alarms «Nahe am Hoch/Tief». */
    fun windowLabel(context: Context, days: Int): String =
        if (days >= 365) context.getString(R.string.alarm_near_window_year)
        else context.resources.getQuantityString(R.plurals.alarm_near_window_days, days, days)

    /** «30-Tage-Hoch», «Jahrestief» … für Satz und Benachrichtigung. */
    fun extremeLabel(context: Context, high: Boolean, days: Int): String = context.getString(
        when (NearExtreme.windowDays(days)) {
            90 -> if (high) R.string.alarm_near_extreme_high_90 else R.string.alarm_near_extreme_low_90
            365 -> if (high) R.string.alarm_near_extreme_high_365 else R.string.alarm_near_extreme_low_365
            else -> if (high) R.string.alarm_near_extreme_high_30 else R.string.alarm_near_extreme_low_30
        }
    )

    /** «Neues 30-Tage-Hoch», «Neues Jahrestief» — Titel und Schnell-Alarm. */
    fun newExtremeLabel(context: Context, high: Boolean, days: Int): String =
        context.getString(R.string.alarm_new_extreme, extremeLabel(context, high, days))

    /**
     * Benachrichtigung «BTC ist 1,6 % unter dem 30-Tage-Hoch (98’450 / 100’050)» bzw.
     * «Neues 30-Tage-Hoch für BTC: 101’200 (bisher 100’050)». Kurse in [currency].
     */
    fun nearExtremeText(
        context: Context,
        alarm: AlarmEntity,
        symbol: String,
        currency: String,
        price: Double,
        fire: NearExtreme.Decision.Fire,
    ): String {
        val high = alarm.condition == AlarmCondition.NEAR_HIGH
        val label = extremeLabel(context, high, alarm.windowHours)
        val now = PriceFormat.priceWithCurrency(price, currency)
        val extreme = PriceFormat.price(fire.extreme)
        if (fire.newExtreme) {
            return context.getString(R.string.alarm_near_notification_new, label, symbol, now, extreme)
        }
        val locale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val distance = AlarmSentence.percent(roundDistance(fire.distancePercent), locale)
        return context.getString(
            if (high) R.string.alarm_near_notification_high else R.string.alarm_near_notification_low,
            symbol, distance, label, PriceFormat.price(price), extreme,
        )
    }

    /** Abstand auf eine Nachkommastelle (1.63 → 1.6), damit die Meldung ruhig bleibt. */
    private fun roundDistance(value: Double): Double = Math.round(value * 10.0) / 10.0

    /** Faktor ohne überflüssige Nachkommastellen: 3 → "3", 4.25 → "4.3" — in den Ziffern der App-Sprache. */
    fun factor(value: Double): String = LocaleNumbers.decimal(value, 1, minDecimals = 0)
}
