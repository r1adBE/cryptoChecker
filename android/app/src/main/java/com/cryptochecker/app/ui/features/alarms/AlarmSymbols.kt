package com.cryptochecker.app.ui.features.alarms

import androidx.annotation.DrawableRes
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition

/**
 * Richtungs-Symbol je Bedingung — in der Alarm-Übersicht vor dem Titel und in den Bedingungs-Chips des Editors.
 * Gleiche Bildsprache wie iOS `AlarmStyle.symbol` (SF Symbols).
 */
internal object AlarmSymbols {
    @DrawableRes
    fun icon(condition: AlarmCondition): Int = when (condition) {
        AlarmCondition.PRICE_ABOVE -> R.drawable.ic_alarm_price_above
        AlarmCondition.PRICE_BELOW -> R.drawable.ic_alarm_price_below
        AlarmCondition.CHANGE_PERCENT_UP -> R.drawable.ic_trending_up
        AlarmCondition.CHANGE_PERCENT_DOWN -> R.drawable.ic_trending_down
        AlarmCondition.MOVE_PERCENT_WINDOW -> R.drawable.ic_alarm_move_window
        AlarmCondition.VOLUME_SPIKE -> R.drawable.ic_alarm_volume
        AlarmCondition.NEAR_HIGH -> R.drawable.ic_alarm_near_high
        AlarmCondition.NEAR_LOW -> R.drawable.ic_alarm_near_low
        AlarmCondition.FUNDING_ABOVE -> R.drawable.ic_alarm_funding_above
        AlarmCondition.FUNDING_BELOW -> R.drawable.ic_alarm_funding_below
        AlarmCondition.OI_UP -> R.drawable.ic_alarm_oi_up
        AlarmCondition.OI_DOWN -> R.drawable.ic_alarm_oi_down
    }
}
