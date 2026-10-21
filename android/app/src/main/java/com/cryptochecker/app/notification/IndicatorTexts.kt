package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.IndicatorId
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.app.util.PriceFormat

/** Name und Wert einer Bitcoin-Kennzahl für Mitteilungen (ohne Compose) — wie `indicatorName`/`indicatorValue`. */
object IndicatorTexts {
    fun name(context: Context, id: IndicatorId): String = when (id) {
        IndicatorId.MAYER -> "Mayer Multiple"
        IndicatorId.MVRV -> "MVRV"
        IndicatorId.PUELL -> "Puell Multiple"
        IndicatorId.ATH_DISTANCE -> context.getString(R.string.ind_drawdown)
        IndicatorId.PI_CYCLE_GAP -> context.getString(R.string.neutral_ind_pi_gap)
        else -> id.name
    }

    fun value(id: IndicatorId, v: Double): String = when (id) {
        IndicatorId.MAYER, IndicatorId.MVRV, IndicatorId.PUELL -> LocaleNumbers.decimal(v, 2) + "×"
        // Abstand als positive Zahl («3.2%», «3,2 %»), damit «unter deiner Grenze 5 %» stimmt
        IndicatorId.ATH_DISTANCE -> PercentText.unsigned(v, 1)
        else -> PriceFormat.signedPercent(v, 1)
    }
}
