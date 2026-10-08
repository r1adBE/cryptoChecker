package com.cryptochecker.app.ui.features.alarms

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.alarm.AlarmSentence
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.util.PriceFormat

/**
 * «Sag mir Bescheid, wenn BTC über 60’000 CHF steigt.» — der Alarm als Satz für
 * Vorschau im Editor und Zeile in der Alarmliste.
 * @param symbol Basis-Asset des Paars (z. B. «BTC»)
 * @param currency Währung eines Kurs-Schwellwerts (Alarmwährung, sonst Quote)
 * @return alarm_sentence_incomplete bei leerer/ungültiger Eingabe
 */
fun alarmSentence(
    context: Context,
    condition: AlarmCondition,
    symbol: String,
    threshold: Double?,
    currency: String,
    windowHours: Int,
): String {
    val kind = when (condition) {
        AlarmCondition.PRICE_ABOVE -> AlarmSentence.Kind.ABOVE
        AlarmCondition.PRICE_BELOW -> AlarmSentence.Kind.BELOW
        AlarmCondition.CHANGE_PERCENT_UP -> AlarmSentence.Kind.UP_PERCENT
        AlarmCondition.CHANGE_PERCENT_DOWN -> AlarmSentence.Kind.DOWN_PERCENT
        AlarmCondition.MOVE_PERCENT_WINDOW -> AlarmSentence.Kind.MOVE_PERCENT
        AlarmCondition.VOLUME_SPIKE -> AlarmSentence.Kind.VOLUME
        AlarmCondition.NEAR_HIGH -> AlarmSentence.Kind.NEAR_HIGH
        AlarmCondition.NEAR_LOW -> AlarmSentence.Kind.NEAR_LOW
        AlarmCondition.FUNDING_ABOVE -> AlarmSentence.Kind.FUNDING_ABOVE
        AlarmCondition.FUNDING_BELOW -> AlarmSentence.Kind.FUNDING_BELOW
        AlarmCondition.OI_UP -> AlarmSentence.Kind.OI_UP
        AlarmCondition.OI_DOWN -> AlarmSentence.Kind.OI_DOWN
    }
    // Sprache der App (kann von der Systemsprache abweichen)
    val locale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
    val parts = AlarmSentence.parts(
        kind = kind,
        symbol = symbol,
        threshold = threshold,
        currency = currency,
        windowHours = windowHours,
        locale = locale,
        formatPrice = { value, code -> PriceFormat.priceWithCurrency(value, code) },
    ) ?: return context.getString(R.string.alarm_sentence_incomplete)

    return when (parts.kind) {
        AlarmSentence.Kind.ABOVE -> context.getString(R.string.alarm_sentence_above, parts.symbol, parts.value)
        AlarmSentence.Kind.BELOW -> context.getString(R.string.alarm_sentence_below, parts.symbol, parts.value)
        AlarmSentence.Kind.UP_PERCENT -> context.getString(R.string.alarm_sentence_up_percent, parts.symbol, parts.value)
        AlarmSentence.Kind.DOWN_PERCENT -> context.getString(R.string.alarm_sentence_down_percent, parts.symbol, parts.value)
        AlarmSentence.Kind.MOVE_PERCENT -> context.resources.getQuantityString(
            R.plurals.alarm_sentence_move_percent, parts.windowHours, parts.symbol, parts.value, parts.windowHours
        )
        AlarmSentence.Kind.VOLUME -> context.getString(R.string.alarm_sentence_volume, parts.symbol, parts.value)
        // «Sag mir Bescheid, wenn BTC höchstens 2 % unter dem 30-Tage-Hoch liegt.» (windowHours = Tage)
        AlarmSentence.Kind.NEAR_HIGH -> context.getString(
            R.string.alarm_sentence_near_high, parts.symbol, parts.value,
            AlarmTexts.extremeLabel(context, high = true, days = parts.windowHours)
        )
        AlarmSentence.Kind.NEAR_LOW -> context.getString(
            R.string.alarm_sentence_near_low, parts.symbol, parts.value,
            AlarmTexts.extremeLabel(context, high = false, days = parts.windowHours)
        )
        // Nur neue Hochs/Tiefs: «Sag mir Bescheid, wenn BTC ein neues 30-Tage-Hoch erreicht.»
        AlarmSentence.Kind.NEW_HIGH -> context.getString(
            R.string.alarm_sentence_new_extreme, parts.symbol,
            AlarmTexts.extremeLabel(context, high = true, days = parts.windowHours)
        )
        AlarmSentence.Kind.NEW_LOW -> context.getString(
            R.string.alarm_sentence_new_extreme, parts.symbol,
            AlarmTexts.extremeLabel(context, high = false, days = parts.windowHours)
        )
        // «Sag mir Bescheid, wenn das Funding von BTC über 0,05 % steigt.»
        AlarmSentence.Kind.FUNDING_ABOVE -> context.getString(R.string.alarm_sentence_funding_above, parts.symbol, parts.value)
        AlarmSentence.Kind.FUNDING_BELOW -> context.getString(R.string.alarm_sentence_funding_below, parts.symbol, parts.value)
        // «Sag mir Bescheid, wenn das Open Interest von BTC innerhalb von 4 Stunden um 10 % steigt.»
        AlarmSentence.Kind.OI_UP -> context.resources.getQuantityString(
            R.plurals.alarm_sentence_oi_up, parts.windowHours, parts.symbol, parts.value, parts.windowHours
        )
        AlarmSentence.Kind.OI_DOWN -> context.resources.getQuantityString(
            R.plurals.alarm_sentence_oi_down, parts.windowHours, parts.symbol, parts.value, parts.windowHours
        )
    }
}
