package com.cryptochecker.app.widget

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.FearGreedLevel
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PlainDirection
import com.cryptochecker.app.domain.market.PlainDirectionKind
import com.cryptochecker.app.domain.market.PlainMood
import com.cryptochecker.app.domain.market.PlainMoodKind
import com.cryptochecker.app.domain.market.PlainStandout
import com.cryptochecker.app.domain.market.PlainStandoutKind
import com.cryptochecker.app.domain.market.PlainSummary
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseLeadKind
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.app.util.PriceFormat

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
        // Fertig formatiert (Ziffern, Abstand und Zeichen der Sprache) statt eines festen «%» in der Übersetzung
        val pct = PercentText.unsigned(lead.volumePercent.toDouble(), 0)
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

    /** «Fear & Greed 72 · Gier» (Stufen wie die Karte im Markt-Tab). */
    fun fearGreed(context: Context, value: Int): String {
        val label = context.getString(
            when (ActivityAnalyzer.fearGreedLevel(value)) {
                FearGreedLevel.EXTREME_FEAR -> R.string.fng_extreme_fear
                FearGreedLevel.FEAR -> R.string.fng_fear
                FearGreedLevel.NEUTRAL -> R.string.fng_neutral
                FearGreedLevel.GREED -> R.string.fng_greed
                FearGreedLevel.EXTREME_GREED -> R.string.fng_extreme_greed
            }
        )
        return context.getString(R.string.factor_fear_greed) + " " + LocaleNumbers.integer(value) + " · " + label
    }

    /**
     * «Kurz gesagt»: die vorhandenen Sätze in der Reihenfolge der Karte (Richtung, Stimmung, was
     * heraussticht) — dieselben Schlüssel und Formate wie `MarketPlainSummaryCard`, ohne Compose.
     */
    fun plainSentences(context: Context, summary: PlainSummary): List<String> = listOfNotNull(
        summary.direction?.let { plainDirection(context, it) },
        summary.mood?.let { plainMood(context, it) },
        summary.standout?.let { plainStandout(context, it) },
    )

    private fun plainDirection(context: Context, direction: PlainDirection): String = context.getString(
        when (direction.kind) {
            PlainDirectionKind.FLAT -> R.string.market_plain_direction_flat
            PlainDirectionKind.UP_SLIGHT -> R.string.market_plain_direction_up_slight
            PlainDirectionKind.UP_CLEAR -> R.string.market_plain_direction_up_clear
            PlainDirectionKind.DOWN_SLIGHT -> R.string.market_plain_direction_down_slight
            PlainDirectionKind.DOWN_CLEAR -> R.string.market_plain_direction_down_clear
        },
        PriceFormat.signedPercent(direction.changePercent, 1)
    )

    private fun plainMood(context: Context, mood: PlainMood): String = context.getString(
        when (mood.kind) {
            PlainMoodKind.EXTREME_FEAR -> R.string.market_plain_mood_extreme_fear
            PlainMoodKind.FEAR -> R.string.market_plain_mood_fear
            PlainMoodKind.NEUTRAL -> R.string.market_plain_mood_neutral
            PlainMoodKind.GREED -> R.string.market_plain_mood_greed
            PlainMoodKind.EXTREME_GREED -> R.string.market_plain_mood_extreme_greed
        },
        mood.value
    )

    private fun plainStandout(context: Context, standout: PlainStandout): String {
        val coin = standout.coin.orEmpty()
        fun percent(value: Double?) = PriceFormat.signedPercent(value ?: 0.0, 1)
        return when (standout.kind) {
            PlainStandoutKind.ABOVE_BTC ->
                context.getString(R.string.market_plain_standout_above_btc, coin, percent(standout.change24h), percent(standout.btc24h))
            PlainStandoutKind.BELOW_BTC ->
                context.getString(R.string.market_plain_standout_below_btc, coin, percent(standout.change24h), percent(standout.btc24h))
            PlainStandoutKind.AGAINST_BTC ->
                context.getString(R.string.market_plain_standout_against_btc, coin, percent(standout.change24h), percent(standout.btc24h))
            PlainStandoutKind.VOLUME ->
                context.getString(R.string.market_plain_standout_volume, coin, LocaleNumbers.decimal(standout.volumeRatio ?: 0.0, 1))
            PlainStandoutKind.FUTURES_UP -> context.getString(R.string.market_plain_standout_futures_up, coin)
            PlainStandoutKind.FUTURES_DOWN -> context.getString(R.string.market_plain_standout_futures_down, coin)
            PlainStandoutKind.QUIET -> context.getString(R.string.market_plain_standout_quiet)
        }
    }
}
