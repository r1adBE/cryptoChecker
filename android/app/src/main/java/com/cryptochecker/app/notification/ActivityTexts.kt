package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.SignalKind
import kotlin.math.abs

/**
 * Texte und Zahlenformate für «Ungewöhnliche Aktivität». Dieselben Sätze in
 * Benachrichtigung, Liste und «Warum»-Blatt.
 */
object ActivityTexts {

    /** Ein Satz je Signal, z. B. «Ungewöhnliche Bewegung: +4.2 % in einer Stunde (3.4× normal)». */
    fun signal(context: Context, signal: ActivitySignal): String = when (signal.kind) {
        SignalKind.PRICE_MOVE -> context.getString(
            R.string.activity_signal_price_move,
            percent(signal.value, 1),
            factor(signal.factor ?: 0.0)
        )
        SignalKind.VOLUME_SPIKE -> context.getString(R.string.activity_signal_volume, factor(signal.value))
        SignalKind.OPEN_INTEREST_JUMP -> {
            val minutes = signal.factor?.toInt()
            if (minutes != null) context.getString(R.string.activity_signal_open_interest, percent(signal.value, 1), minutes)
            else context.getString(R.string.activity_signal_open_interest_no_time, percent(signal.value, 1))
        }
        SignalKind.FUNDING_EXTREME -> context.getString(
            if (signal.value >= 0) R.string.activity_signal_funding_long else R.string.activity_signal_funding_short,
            percent(signal.value, 3)
        )
    }

    /** «+2.9 %» / «−2.9 %» / «0.0 %», Vorzeichen als echtes Minus. */
    fun percent(value: Double, decimals: Int): String {
        val rounded = "%.${decimals}f".format(abs(value))
        val isZero = rounded.all { it == '0' || it == '.' || it == ',' }
        val sign = when {
            isZero -> ""
            value > 0 -> "+"
            else -> "−"
        }
        return "$sign$rounded %"
    }

    /** «4.2×»-Zahl ohne Zeichen, eine Nachkommastelle. */
    fun factor(value: Double): String = "%.1f".format(value)
}
