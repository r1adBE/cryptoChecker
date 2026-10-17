package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat

/**
 * Texte der Portfolio-Alarme (Alarmliste, Portfolio und Benachrichtigung). Beträge werden mit
 * «Beträge verbergen» ([hidden]) zu «•••»; Prozente bleiben sichtbar.
 */
object PortfolioAlarmTexts {

    /** «50’000.00 CHF» bzw. «5.00%». */
    fun threshold(alarm: PortfolioAlarmEntity, hidden: Boolean): String =
        if (alarm.kind.isValue) {
            PortfolioInsights.mask(PriceFormat.valueWithCurrency(alarm.threshold, alarm.currency ?: "USD"), hidden)
        } else {
            PortfolioSnapshotMath.unsignedPercent(alarm.threshold)
        }

    /** «Portfolio über 50’000.00 CHF», «Portfolio fällt um 5.00% (24h)». */
    fun sentence(context: Context, alarm: PortfolioAlarmEntity, basis: ChangeBasis, hidden: Boolean): String {
        val value = threshold(alarm, hidden)
        return when (alarm.kind) {
            PortfolioAlarmKind.VALUE_ABOVE -> context.getString(R.string.portfolio_alarm_sentence_above, value)
            PortfolioAlarmKind.VALUE_BELOW -> context.getString(R.string.portfolio_alarm_sentence_below, value)
            PortfolioAlarmKind.CHANGE_UP ->
                context.getString(R.string.portfolio_alarm_sentence_up, value, ChangeBasisText.shortLabel(context, basis))
            PortfolioAlarmKind.CHANGE_DOWN ->
                context.getString(R.string.portfolio_alarm_sentence_down, value, ChangeBasisText.shortLabel(context, basis))
        }
    }

    /** Gemessener Wert beim Auslösen: «50’210.00 CHF» bzw. «−5.20%». */
    fun measured(alarm: PortfolioAlarmEntity, measured: Double, hidden: Boolean): String = when (alarm.kind) {
        PortfolioAlarmKind.VALUE_ABOVE, PortfolioAlarmKind.VALUE_BELOW ->
            PortfolioInsights.mask(PriceFormat.valueWithCurrency(measured, alarm.currency ?: "USD"), hidden)
        // Die «fällt»-Alarme messen gespiegelt (−Veränderung); gezeigt wird die echte Veränderung
        PortfolioAlarmKind.CHANGE_UP -> PortfolioSnapshotMath.signedPercent(measured)
        PortfolioAlarmKind.CHANGE_DOWN -> PortfolioSnapshotMath.signedPercent(-measured)
    }

    /** Name der Art für die Auswahl beim Anlegen. */
    fun kindLabel(context: Context, kind: PortfolioAlarmKind): String = context.getString(
        when (kind) {
            PortfolioAlarmKind.VALUE_ABOVE -> R.string.portfolio_alarm_kind_above
            PortfolioAlarmKind.VALUE_BELOW -> R.string.portfolio_alarm_kind_below
            PortfolioAlarmKind.CHANGE_UP -> R.string.portfolio_alarm_kind_up
            PortfolioAlarmKind.CHANGE_DOWN -> R.string.portfolio_alarm_kind_down
        }
    )
}
