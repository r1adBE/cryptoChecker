package com.cryptochecker.app.widget

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseLeadKind
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary

/**
 * Texte des Widgets «Was gerade auffällt» — dieselben Schlüssel wie die Karte im Markt-Tab
 * (CryptoPulseCard), nur ohne Compose, damit sie im RemoteViews-Pfad gehen.
 */
internal object PulseWidgetTexts {

    fun headline(context: Context, summary: PulseSummary): String = context.getString(
        when (summary) {
            PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> R.string.pulse_headline_up
            PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME -> R.string.pulse_headline_down
            PulseSummary.MIXED -> R.string.pulse_headline_mixed
            PulseSummary.CALM -> R.string.pulse_headline_calm
        }
    )

    /** Leitsatz aus [CryptoPulse.leadSentence]: ein oder zwei ganze Sätze. */
    fun lead(context: Context, report: PulseReport): String {
        val lead = CryptoPulse.leadSentence(report)
        val first = context.getString(
            when (lead.kind) {
                PulseLeadKind.BTC_LEADS -> R.string.pulse_lead_btc_leads
                PulseLeadKind.ALTS_STRONGER -> R.string.pulse_lead_alts_stronger
                PulseLeadKind.BTC_STRONGER -> R.string.pulse_lead_btc_stronger
                PulseLeadKind.BROAD_UP -> R.string.pulse_lead_broad_up
                PulseLeadKind.BROAD_DOWN -> R.string.pulse_lead_broad_down
                PulseLeadKind.DRIFT_UP -> R.string.pulse_lead_drift_up
                PulseLeadKind.DRIFT_DOWN -> R.string.pulse_lead_drift_down
                PulseLeadKind.MIXED -> R.string.pulse_lead_mixed
                PulseLeadKind.CALM -> R.string.pulse_lead_calm
            }
        )
        val pct = lead.volumePercent
        val second = when (lead.detail) {
            PulseDetail.VOL_ABOVE -> context.getString(R.string.pulse_detail_vol_above, pct)
            PulseDetail.VOL_ABOVE_FUND_NEUTRAL -> context.getString(R.string.pulse_detail_vol_above_fund_neutral, pct)
            PulseDetail.VOL_ABOVE_FUND_HIGH -> context.getString(R.string.pulse_detail_vol_above_fund_high, pct)
            PulseDetail.VOL_ABOVE_FUND_NEGATIVE -> context.getString(R.string.pulse_detail_vol_above_fund_negative, pct)
            PulseDetail.VOL_BELOW -> context.getString(R.string.pulse_detail_vol_below, pct)
            PulseDetail.VOL_BELOW_FUND_NEUTRAL -> context.getString(R.string.pulse_detail_vol_below_fund_neutral, pct)
            PulseDetail.VOL_BELOW_FUND_HIGH -> context.getString(R.string.pulse_detail_vol_below_fund_high, pct)
            PulseDetail.VOL_BELOW_FUND_NEGATIVE -> context.getString(R.string.pulse_detail_vol_below_fund_negative, pct)
            PulseDetail.VOL_NORMAL -> context.getString(R.string.pulse_detail_vol_normal)
            PulseDetail.VOL_NORMAL_FUND_NEUTRAL -> context.getString(R.string.pulse_detail_vol_normal_fund_neutral)
            PulseDetail.VOL_NORMAL_FUND_HIGH -> context.getString(R.string.pulse_detail_vol_normal_fund_high)
            PulseDetail.VOL_NORMAL_FUND_NEGATIVE -> context.getString(R.string.pulse_detail_vol_normal_fund_negative)
            PulseDetail.FUND_NEUTRAL -> context.getString(R.string.pulse_detail_fund_neutral)
            PulseDetail.FUND_HIGH -> context.getString(R.string.pulse_detail_fund_high)
            PulseDetail.FUND_NEGATIVE -> context.getString(R.string.pulse_detail_fund_negative)
            null -> null
        }
        return if (second == null) first else "$first $second"
    }
}
