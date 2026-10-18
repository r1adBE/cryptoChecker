package com.cryptochecker.app.ui.features.alarms

import android.content.Context
import android.text.format.DateUtils
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmLogOutcome
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat

/**
 * Texte des Alarm-Protokolls ([com.cryptochecker.app.domain.alarm.AlarmLog]): Ergebnis, Schwelle, gesehener Kurs und
 * die Zeile «Zuletzt geprüft» im Alarm-Editor. Wie `AlarmLogTexts` (iOS).
 */
internal object AlarmLogTexts {

    /** Ergebnis in klaren Worten, z. B. «Nicht ausgelöst – Kurs veraltet (Stand 11:40)». */
    fun outcome(context: Context, entry: AlarmLogEntry): String = when (entry.outcome) {
        AlarmLogOutcome.FIRED -> context.getString(R.string.alarm_outcome_fired)
        AlarmLogOutcome.NOT_REACHED -> context.getString(R.string.alarm_outcome_not_reached)
        AlarmLogOutcome.ALREADY_TRIGGERED -> context.getString(R.string.alarm_outcome_already_triggered)
        AlarmLogOutcome.COOLDOWN -> context.getString(R.string.alarm_outcome_cooldown)
        AlarmLogOutcome.STALE -> context.getString(
            R.string.alarm_outcome_stale,
            entry.exchangeTime?.let { time(context, it) } ?: "—",
        )
        AlarmLogOutcome.MARK_PRICE -> context.getString(R.string.alarm_outcome_mark_price)
        AlarmLogOutcome.LOW_LIQUIDITY -> context.getString(R.string.alarm_outcome_low_liquidity)
        AlarmLogOutcome.QUIET_HOURS -> context.getString(R.string.alarm_outcome_quiet_hours)
        AlarmLogOutcome.SNOOZED -> context.getString(R.string.alarm_outcome_snoozed)
        AlarmLogOutcome.NOTIFICATIONS_OFF -> context.getString(R.string.alarm_outcome_notifications_off)
        AlarmLogOutcome.NOT_SHOWN -> context.getString(R.string.alarm_outcome_not_shown)
        AlarmLogOutcome.NO_RATE -> context.getString(R.string.alarm_outcome_no_rate)
        AlarmLogOutcome.NO_PRICE -> context.getString(R.string.alarm_outcome_no_price)
    }

    /** Wer geprüft hat, z. B. «über Widget»; null bei älteren Einträgen ohne Angabe. */
    fun source(context: Context, entry: AlarmLogEntry): String? = when (entry.source) {
        null -> null
        CheckTrigger.FOREGROUND -> context.getString(R.string.alarm_log_source_app)
        CheckTrigger.WORKER, CheckTrigger.BG_TASK -> context.getString(R.string.alarm_log_source_background)
        CheckTrigger.EXACT -> context.getString(R.string.alarm_log_source_exact)
        CheckTrigger.WIDGET -> context.getString(R.string.alarm_log_source_widget)
    }

    /** Ergebnis mit Herkunft für die Zeile des Protokolls: «Ausgelöst · über Widget». */
    fun outcomeWithSource(context: Context, entry: AlarmLogEntry): String =
        listOfNotNull(outcome(context, entry), source(context, entry)).joinToString(" · ")

    /** Schwelle wie in der Alarm-Übersicht, z. B. «Über 100’000 CHF». */
    fun threshold(context: Context, entry: AlarmLogEntry): String {
        val alarm = AlarmEntity(
            id = entry.alarmId,
            watchId = 0,
            condition = entry.condition,
            threshold = entry.threshold,
            windowHours = entry.windowHours,
            currency = entry.currency,
        )
        val suffix = if (entry.condition.isPriceThreshold && entry.currency == null) " ${entry.quote}" else ""
        return AlarmTexts.describe(context, alarm) + suffix
    }

    /** Börse und gesehener Kurs, z. B. «Kraken 99’800 CHF» (Schwelle in eigener Währung: umgerechnet); ohne Kurs nur die Börse. */
    fun seen(entry: AlarmLogEntry): String {
        val shown = entry.shownPrice
        val price = when {
            entry.currency != null && shown != null -> PriceFormat.priceWithCurrency(shown, entry.currency)
            entry.price != null -> PriceFormat.priceWithCurrency(entry.price, entry.quote)
            else -> null
        }
        val exchange = BidiText.isolate(entry.exchange)
        return if (price == null) exchange else "$exchange $price"
    }

    /** Zeit: heute nur die Uhrzeit, sonst mit Datum; mehrmals hintereinander als «von–bis». */
    fun time(context: Context, entry: AlarmLogEntry): String =
        if (entry.until > entry.at) "${time(context, entry.at)}–${time(context, entry.until)}" else time(context, entry.at)

    fun time(context: Context, millis: Long): String {
        val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
        else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateTime(context, millis, flags)
    }

    /** «Zuletzt geprüft» im Editor: «14:02 · Kraken 99’800 CHF · Marke nicht erreicht». */
    fun lastCheckLine(context: Context, entry: AlarmLogEntry): String =
        context.getString(
            R.string.alarm_last_check,
            listOf(time(context, entry.at), seen(entry), outcome(context, entry)).joinToString(" · "),
        )

    /** Ganze Zeile des Protokolls für den Screenreader. */
    fun rowDescription(context: Context, entry: AlarmLogEntry): String =
        listOf(
            time(context, entry),
            entry.pair,
            seen(entry),
            context.getString(R.string.alarm_log_threshold, threshold(context, entry)),
            outcome(context, entry),
            source(context, entry),
        ).filterNotNull().joinToString(", ")
}
