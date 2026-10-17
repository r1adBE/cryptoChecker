package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.SignalKind
import com.cryptochecker.app.util.BidiText
import java.util.Locale
import kotlin.math.abs

/**
 * Texte und Zahlenformate für «Ungewöhnliche Aktivität». Dieselben Sätze in
 * Benachrichtigung, Liste und «Was bewegt sich mit?»-Blatt.
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
            fundingValue(context, signal.value, signal.factor?.toInt())
        )
    }

    /** «+2.9 %» / «−2.9 %» / «0.0 %», Vorzeichen als echtes Minus. */
    fun percent(value: Double, decimals: Int): String {
        val rounded = "%.${decimals}f".format(abs(value))
        // Nullprüfung mit lateinischen Ziffern (die Anzeige kann arabische Ziffern haben)
        val isZero = "%.${decimals}f".format(Locale.ROOT, abs(value)).all { it == '0' || it == '.' }
        val sign = when {
            isZero -> ""
            value > 0 -> "+"
            else -> "−"
        }
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr("$sign$rounded %")
    }

    /**
     * Funding-Satz der Börse mit drei Nachkommastellen; nicht alle 8 Stunden abgerechnet mit «pro Std.»
     * bzw. «pro 4 Std.» ([intervalHours]), damit der Wert nicht wie ein 8-Stunden-Satz gelesen wird.
     */
    fun fundingValue(context: Context, value: Double, intervalHours: Int?): String {
        val text = percent(value, 3)
        return when {
            intervalHours == 1 -> text + " " + context.getString(R.string.futures_funding_per_hour)
            intervalHours != null && intervalHours != 8 && intervalHours in 2..24 ->
                text + " " + context.getString(R.string.futures_funding_per_hours, intervalHours)
            else -> text
        }
    }

    /** «4.2×»-Zahl ohne Zeichen, eine Nachkommastelle. */
    fun factor(value: Double): String = "%.1f".format(value)
}
