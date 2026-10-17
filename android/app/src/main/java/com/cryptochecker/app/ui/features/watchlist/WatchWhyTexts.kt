// Texte von «Was bewegt sich mit?»: Kurzsatz, Faktor-Titel/Werte/Hinweise, Funding, Open Interest, Angst & Gier,
// Referenzmarkt, Quellen- und Stand-Zeilen.
// Eigene Datei, weil es reine Textregeln sind; die Karten in `WatchWhyFactors.kt` und das Blatt
// `WatchWhySheet.kt` nutzen sie gemeinsam (wie `WatchWhyTexts.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.FearGreedLevel
import com.cryptochecker.app.domain.activity.FundingTrend
import com.cryptochecker.app.domain.activity.FundingTrendDirection
import com.cryptochecker.app.domain.activity.FuturesSourceNote
import com.cryptochecker.app.domain.activity.MarketRef
import com.cryptochecker.app.domain.activity.WhyBrief
import com.cryptochecker.app.domain.activity.WhyExtra
import com.cryptochecker.app.domain.activity.WhyFactor
import com.cryptochecker.app.domain.activity.WhyFactorKind
import com.cryptochecker.app.domain.activity.WhyFactorNote
import com.cryptochecker.app.domain.activity.WhyFreshness
import com.cryptochecker.app.domain.activity.WhyHeadline
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySourceItem
import com.cryptochecker.app.domain.activity.WhySourceRole
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.roundToInt

/**
 * Kurzer Text eines Faktors in «Kurz gesagt»: Titel und Wert («Volumen 2.4×»); Gesamtmarkt nur der Wert («BTC +1.8 %»),
 * Open Interest als «OI +12 %»; ohne Wert nur der Titel («Nähe zum Hoch»).
 */
internal fun whyBriefText(factor: WhyFactor, texts: FactorTexts): String = when {
    texts.value.isEmpty() -> texts.title
    factor.kind == WhyFactorKind.MARKET -> texts.value
    factor.kind == WhyFactorKind.OPEN_INTEREST -> "OI " + texts.value
    else -> texts.title + " " + texts.value
}

/** «Kurz gesagt»: genau ein Satz — die Einordnung, sonst der erste Zusatz; null = keiner. */
@Composable
internal fun briefSentence(brief: WhyBrief): String? {
    brief.headline?.let { headline ->
        return stringResource(
            when (headline) {
                WhyHeadline.CALM -> R.string.why_summary_calm
                WhyHeadline.COIN_VOLUME -> R.string.why_summary_coin_volume
                WhyHeadline.COIN -> R.string.why_summary_coin
                WhyHeadline.AGAINST -> R.string.why_summary_against
                WhyHeadline.MARKET_VOLUME -> R.string.why_summary_market_volume
                WhyHeadline.MARKET -> R.string.why_summary_market
                WhyHeadline.PEG_NEAR -> R.string.why_summary_peg_near
                WhyHeadline.PEG_OFF -> R.string.why_summary_peg_off
            }
        )
    }
    return brief.extras.firstOrNull()?.let { extra ->
        stringResource(
            when (extra) {
                WhyExtra.THIN -> R.string.why_summary_thin
                WhyExtra.LONGS -> R.string.why_summary_longs
                WhyExtra.SHORTS -> R.string.why_summary_shorts
            }
        )
    }
}

/** Texte einer Faktorzeile: Titel, Wert rechts, Nebenzeile und der gesprochene Satz. */
internal data class FactorTexts(val title: String, val value: String, val note: String, val spoken: String)

@Composable
internal fun factorTexts(f: WhyFactor): FactorTexts {
    val context = LocalContext.current
    fun pct(v: Double, decimals: Int = 1) = ActivityTexts.percent(v, decimals)
    val title: String
    var value = ""
    var spokenValue = ""
    var note: String
    when (f.kind) {
        WhyFactorKind.VOLUME -> {
            val number = ActivityTexts.factor(f.value)
            title = stringResource(R.string.factor_volume)
            value = "$number×"
            spokenValue = stringResource(R.string.factor_spoken_volume, number)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.VOLUME_HIGHER -> R.string.why_fn_volume_higher
                    WhyFactorNote.VOLUME_LOWER -> R.string.why_fn_volume_lower
                    else -> R.string.why_fn_volume_usual
                }
            )
            // Ohne Quote-Umsatz der Quelle nur genähert
            if (f.approximate) note += " · " + stringResource(R.string.why_fn_estimated)
        }
        WhyFactorKind.MARKET -> {
            title = stringResource(R.string.why_factor_market)
            // Bei Bitcoin selbst steht BTC oben: rechts ETH zum Vergleich
            val leader = f.note == WhyFactorNote.MARKET_LEADER_MOVES || f.note == WhyFactorNote.MARKET_LEADER_CALM
            val symbol = if (leader) "ETH" else marketRefLabel(f.ref)
            val change = if (leader) f.secondary else f.value
            if (change != null) {
                value = "$symbol " + pct(change)
                spokenValue = "$symbol, " + A11yText.change(context, change, decimals = 1)
            }
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.MARKET_PULLS -> R.string.why_fn_market_pulls
                    WhyFactorNote.MARKET_MOVES -> R.string.why_fn_market_moves
                    WhyFactorNote.MARKET_COIN_LAGS -> R.string.why_fn_market_lags
                    WhyFactorNote.MARKET_COIN_ALONE -> R.string.why_fn_market_alone
                    WhyFactorNote.MARKET_AGAINST -> R.string.why_fn_market_against
                    WhyFactorNote.MARKET_LEADER_MOVES -> R.string.why_fn_leader_moves
                    WhyFactorNote.MARKET_LEADER_CALM -> R.string.why_fn_leader_calm
                    else -> R.string.why_fn_market_calm
                }
            )
        }
        WhyFactorKind.VOLATILITY -> {
            val number = ActivityTexts.factor(f.value)
            title = stringResource(R.string.factor_volatility)
            value = "$number×"
            spokenValue = stringResource(R.string.factor_spoken_volatility, number)
            note = when (f.note) {
                WhyFactorNote.VOLATILITY_STRONGER -> stringResource(R.string.why_fn_volatility_stronger)
                // «absolut gering (unter 0.5 %)»: Mindestbewegung in der Schreibweise der App-Sprache
                WhyFactorNote.VOLATILITY_TINY -> stringResource(
                    R.string.why_fn_volatility_tiny,
                    BidiText.ltr("%.1f".format(ActivityAnalyzer.VOLATILITY_MIN_MOVE_PERCENT) + " %"),
                )
                else -> stringResource(R.string.why_fn_volatility_usual)
            }
        }
        WhyFactorKind.OPEN_INTEREST -> {
            title = stringResource(R.string.why_factor_futures)
            value = pct(f.value)
            spokenValue = A11yText.change(context, f.value, decimals = 1)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.OI_UP -> R.string.why_fn_oi_up
                    WhyFactorNote.OI_DOWN -> R.string.why_fn_oi_down
                    WhyFactorNote.OI_MIXED -> R.string.why_fn_oi_mixed
                    else -> R.string.why_fn_oi_flat
                }
            )
        }
        WhyFactorKind.NEAR_LOW -> {
            title = stringResource(R.string.why_factor_near_low)
            if (f.note == WhyFactorNote.LOW_ABOVE) {
                value = pct(f.value)
                spokenValue = A11yText.change(context, f.value, decimals = 1)
            }
            note = stringResource(
                if (f.note == WhyFactorNote.LOW_AT) R.string.why_fn_low_at else R.string.why_fn_low_above
            )
        }
        WhyFactorKind.POSITION_3D -> {
            title = stringResource(R.string.why_factor_range3d)
            value = pct(f.value)
            spokenValue = A11yText.change(context, f.value, decimals = 1)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.RANGE_NEAR_HIGH -> R.string.why_fn_range_near_high
                    WhyFactorNote.RANGE_NEAR_LOW -> R.string.why_fn_range_near_low
                    WhyFactorNote.RANGE_BELOW_HIGH -> R.string.why_fn_range_below_high
                    else -> R.string.why_fn_range_above_low
                }
            )
        }
        WhyFactorKind.PEG -> {
            // Abweichung steht in der Nebenzeile («0.12 % unter der 1:1-Bindung»), rechts kein Wert
            title = stringResource(R.string.why_factor_peg)
            // Betrag ohne Vorzeichen (die Richtung sagt «unter»/«über»), als Insel für RTL
            val amount = BidiText.ltr("%.2f".format(kotlin.math.abs(f.value)) + " %")
            note = when (f.note) {
                WhyFactorNote.PEG_BELOW -> stringResource(R.string.why_fn_peg_below, amount)
                WhyFactorNote.PEG_ABOVE -> stringResource(R.string.why_fn_peg_above, amount)
                else -> stringResource(R.string.why_fn_peg_near)
            }
        }
        WhyFactorKind.NEAR_HIGH -> {
            title = stringResource(R.string.why_factor_near_high)
            if (f.note == WhyFactorNote.HIGH_BELOW) {
                value = pct(-f.value)
                spokenValue = A11yText.change(context, -f.value, decimals = 1)
            }
            note = stringResource(
                if (f.note == WhyFactorNote.HIGH_AT) R.string.why_fn_high_at else R.string.why_fn_high_below
            )
        }
        WhyFactorKind.FUNDING -> {
            title = stringResource(R.string.why_factor_funding)
            value = fundingText(f.value, f.intervalHours)
            spokenValue = value
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.FUNDING_LONGS -> R.string.why_fn_funding_longs
                    WhyFactorNote.FUNDING_SHORTS -> R.string.why_fn_funding_shorts
                    else -> R.string.why_fn_funding_neutral
                }
            )
            // Verlauf der abgerechneten Sätze («seit 24 Std. steigend»)
            f.trend?.let { note += " · " + fundingTrendText(it) }
        }
        WhyFactorKind.SENTIMENT -> {
            val points = f.value.roundToInt()
            title = stringResource(R.string.factor_fear_greed)
            value = LocaleNumbers.integer(points)
            spokenValue = value
            note = fearGreedLabel(points)
        }
    }
    val spoken = "$title: $note" + (if (spokenValue.isNotBlank()) ", $spokenValue" else "") + "."
    return FactorTexts(title, value, note, spoken)
}

/** Kürzel der Markt-Referenz vor dem Wert: «BTC», «ETH» oder «Alts» (breiter Markt). */
@Composable
internal fun marketRefLabel(ref: MarketRef?): String = when (ref) {
    MarketRef.ETH -> "ETH"
    MarketRef.BROAD -> stringResource(R.string.why_ref_alts)
    else -> "BTC"
}

/** «seit 24 Std. steigend» / «fallend» / «stabil». */
@Composable
internal fun fundingTrendText(trend: FundingTrend): String = pluralStringResource(
    when (trend.direction) {
        FundingTrendDirection.RISING -> R.plurals.why_trend_rising
        FundingTrendDirection.FALLING -> R.plurals.why_trend_falling
        FundingTrendDirection.STABLE -> R.plurals.why_trend_stable
    },
    trend.hours,
    trend.hours,
)

/** Funding mit drei Nachkommastellen, z. B. «+0.080 %». */
/** Funding-Satz der Börse; stündlich abgerechnet ([intervalHours] = 1, Hyperliquid) mit «pro Std.». */
@Composable
internal fun fundingText(value: Double, intervalHours: Int?): String =
    ActivityTexts.fundingValue(LocalContext.current, value, intervalHours)

@Composable
internal fun withOpenInterest(text: String, change: Double?): String =
    if (change == null) {
        text
    } else {
        text + " " + stringResource(R.string.why_open_interest_change, ActivityTexts.percent(change, 1))
    }

internal fun signedInt(value: Int): String = when {
    value > 0 -> "+" + LocaleNumbers.integer(value)
    value < 0 -> "−" + LocaleNumbers.integer(-value)
    else -> "±" + LocaleNumbers.integer(0)
}

@Composable
internal fun fearGreedLabel(value: Int): String = stringResource(
    when (ActivityAnalyzer.fearGreedLevel(value)) {
        FearGreedLevel.EXTREME_FEAR -> R.string.fng_extreme_fear
        FearGreedLevel.FEAR -> R.string.fng_fear
        FearGreedLevel.NEUTRAL -> R.string.fng_neutral
        FearGreedLevel.GREED -> R.string.fng_greed
        FearGreedLevel.EXTREME_GREED -> R.string.fng_extreme_greed
    }
)

/** Anbieter, bei Bedarf mit «(Ausweichquelle)» bzw. «(Richtwert)». */
@Composable
private fun providerText(name: String, note: FuturesSourceNote?): String = when (note) {
    FuturesSourceNote.FALLBACK -> stringResource(R.string.why_source_fallback, name)
    FuturesSourceNote.REFERENCE -> stringResource(R.string.why_source_reference, name)
    null -> name
}

@Composable
internal fun whyFreshness(report: WhyReport): List<String> {
    val sources = report.sources ?: return emptyList()
    val out = ArrayList<String>()
    WhyFreshness.sources(report).forEach { item ->
        when (item.role) {
            WhySourceRole.CANDLES -> {
                val name = providerText(item.provider, item.note)
                // Ende der letzten abgeschlossenen Stunde
                report.candleTime?.let { out += stringResource(R.string.why_fresh_candles, name, PriceFormat.shortTime(it)) }
            }
            WhySourceRole.BREADTH -> sources.broadTime?.let {
                out += stringResource(R.string.why_fresh_breadth, item.provider, PriceFormat.shortTime(it))
            }
            WhySourceRole.FUTURES -> {
                val head = stringResource(R.string.why_fresh_futures, providerText(item.provider, item.note))
                val oi = report.oiMinutes?.let { minutes ->
                    val hours = WhyFreshness.oiAgeHours(minutes)
                    if (hours != null) {
                        pluralStringResource(R.plurals.why_fresh_oi_hours, hours, hours)
                    } else {
                        pluralStringResource(R.plurals.why_fresh_oi_minutes, minutes, minutes)
                    }
                }
                out += if (oi != null) "$head · $oi" else head
            }
            WhySourceRole.FEAR_GREED -> sources.fearGreedTime?.let { time ->
                val day = when (WhyFreshness.fearGreedDaysAgo(time, report.dataTime)) {
                    0L -> stringResource(R.string.why_fresh_today)
                    1L -> stringResource(R.string.why_fresh_yesterday)
                    else -> utcDate(time)
                }
                out += stringResource(R.string.why_fresh_fng, day)
            }
            WhySourceRole.COMPARISON -> Unit
        }
    }
    return out
}

/** «Quellen: Binance (Kerzen), Bybit (Futures), CoinGecko (Marktbreite), alternative.me (Fear & Greed).»; null ohne Angaben. */
@Composable
internal fun whySourcesLine(report: WhyReport): String? {
    val items = WhyFreshness.sources(report)
    if (items.isEmpty()) return null
    val parts = items.map { item: WhySourceItem ->
        val name = providerText(item.provider, item.note)
        when (item.role) {
            WhySourceRole.CANDLES -> stringResource(R.string.why_src_candles, name)
            WhySourceRole.COMPARISON -> stringResource(R.string.why_src_comparison, name)
            WhySourceRole.FUTURES -> stringResource(R.string.why_src_futures, name)
            WhySourceRole.BREADTH -> stringResource(R.string.why_src_breadth, name)
            WhySourceRole.FEAR_GREED -> stringResource(R.string.why_src_fng, name)
        }
    }
    return stringResource(R.string.why_sources_list, parts.joinToString(", "))
}

/** Datum eines UTC-Tags (Fear & Greed zählt UTC-Tage), kurz in der Sprache des Geräts. */
private fun utcDate(millis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(java.util.Date(millis))
