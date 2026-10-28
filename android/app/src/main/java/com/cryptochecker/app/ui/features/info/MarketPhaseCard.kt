package com.cryptochecker.app.ui.features.info

import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import java.time.LocalDate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.Indicator
import com.cryptochecker.app.domain.market.IndicatorId
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.MonthYearFormat
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.app.util.PriceFormat

/*
 * Reine Messwerte, keine «Marktphase» (keine Zonen, Hoch-/Tief-Punkte, Skala, Phasen nach dem
 * Halving): je Kennzahl der Wert, ein Satz, was sie misst,
 * und eine Zeile «hoch heisst …, tief heisst …» — in normaler Textfarbe, ohne Schwellen,
 * Punkte oder Signal. Wie iOS `MarketPhaseCard`.
 */

/**
 * «Bitcoin-Kennzahlen» als Zeile unter «Einordnung»: rechts der Kurs, darunter Mayer-Multiple
 * und Abstand zum Allzeithoch; Tippen klappt alle Kennzahlen mit Erklärung und Quelle auf.
 */
@Composable
internal fun MarketPhaseRow(
    state: MarketState,
    onRetry: () -> Unit,
    first: Boolean = false,
    /** Herkunft (Kerzen-Anbieter, Coin Metrics) und Stand für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val report = (state as? MarketState.Loaded)?.report
    MarketRow(
        title = stringResource(R.string.neutral_btc_indicators_title),
        secondary = report?.let { indicatorSummary(it.indicators, "USD") }.orEmpty(),
        value = report?.let { PriceFormat.priceWithCurrency(it.price, "USD") },
        first = first,
        loading = state is MarketState.Loading,
        failure = if (state is MarketState.Failed) stringResource(R.string.market_phase_trend_failed) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        info = MarketInfoTopic.PHASE,
    ) {
        if (report != null) {
            IndicatorList(report.indicators, currency = "USD", historySince = report.historySince)
            if (!report.onChainAvailable) {
                Text(
                    text = stringResource(R.string.neutral_onchain_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
        Text(
            text = stringResource(R.string.market_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        if (report?.historySince != null) {
            Text(
                text = stringResource(R.string.ind_context_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        Text(
            text = stringResource(R.string.neutral_indicators_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Nebenzeile: die ersten zwei Kennzahlen mit Wert, z. B. «Mayer-Multiple 1.12× · Abstand zum Hoch 23.4 %». */
@Composable
internal fun indicatorSummary(indicators: List<Indicator>, currency: String): String =
    indicators.filter { it.value != null && it.id != IndicatorId.MA200W }
        .take(2)
        .map { "${indicatorName(it.id)} ${indicatorValue(it, currency)}" }
        .joinToString(" · ")

/**
 * Kennzahlen als Liste: links Name und darunter klein ein Satz, was sie misst, und wie man hohe
 * und tiefe Werte liest; rechts der Wert in normaler Textfarbe (keine Farbe für «gut» oder
 * «schlecht»). Fehlt ein Wert: «zu wenig Daten». Für den Screenreader je Zeile ein Satz.
 */
@Composable
internal fun IndicatorList(indicators: List<Indicator>, currency: String, historySince: LocalDate? = null) {
    indicators.forEach { indicator ->
        val name = indicatorName(indicator.id)
        val value = indicator.value?.let { indicatorValue(indicator, currency) } ?: stringResource(R.string.ind_missing)
        // Einordnung (nur Rechnung): «Seit Juni 2016 an 78 % der Tage tiefer · Bei gleichem Kurs in 30 Tagen: 1.18×»
        val context = indicatorContext(indicator, historySince, currency)
        val explain = stringResource(indicatorExplain(indicator.id)) + "\n" + stringResource(indicatorReading(indicator.id)) +
            context?.let { "\n$it" }.orEmpty()
        val spoken = stringResource(R.string.label_value, name, value) + ". " + explain
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm)
                .clearAndSetSemantics { contentDescription = spoken }
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    explain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * Einordnung einer Kennzahl in ihren Verlauf (IndicatorHistoryMath): Perzentil seit [since] und Wert in 30 Tagen
 * bei gleichem Kurs — beides Rechnung, keine Prognose; null ohne Verlauf.
 */
@Composable
private fun indicatorContext(indicator: Indicator, since: LocalDate?, currency: String): String? {
    val stat = indicator.stat ?: return null
    val parts = ArrayList<String>()
    // Sprache aus der Konfiguration lesen (beobachtbar), nicht Locale.getDefault()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    if (since != null) {
        stat.percentileBelow?.let { p ->
            val month = since.format(MonthYearFormat.formatter(locale))
            parts += stringResource(R.string.ind_context_percentile, month, PercentText.unsigned(p, 0))
        }
    }
    stat.flat30?.let { f ->
        parts += stringResource(R.string.ind_context_flat30, indicatorValue(Indicator(indicator.id, f), currency))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Platzhalter in der Form von [IndicatorList]. */
@Composable
internal fun IndicatorListSkeleton(count: Int) {
    repeat(count) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SkeletonLine(MaterialTheme.typography.bodyMedium, Modifier.width(140.dp))
                SkeletonLine(MaterialTheme.typography.bodySmall, Modifier.fillMaxWidth(0.8f))
            }
            SkeletonLine(MaterialTheme.typography.bodyMedium.tabularNumbers(), Modifier.width(64.dp))
        }
    }
}

@Composable
internal fun indicatorName(id: IndicatorId): String = when (id) {
    IndicatorId.MAYER -> "Mayer Multiple"
    IndicatorId.MA200W -> stringResource(R.string.neutral_ind_ma200w)
    IndicatorId.ATH_DISTANCE -> stringResource(R.string.ind_drawdown)
    IndicatorId.CHANGE_30D -> stringResource(R.string.neutral_ind_change_30d)
    IndicatorId.SMA50_VS_200 -> stringResource(R.string.ind_cross)
    IndicatorId.RSI_WEEKLY -> stringResource(R.string.ind_rsi_weekly)
    IndicatorId.RSI_DAILY -> stringResource(R.string.ind_rsi_daily)
    IndicatorId.VS_BTC -> stringResource(R.string.ind_vs_btc)
    IndicatorId.MVRV -> "MVRV"
    IndicatorId.PUELL -> "Puell Multiple"
    IndicatorId.HASHRATE_30_60 -> stringResource(R.string.neutral_ind_hashrate)
    IndicatorId.PI_CYCLE_GAP -> stringResource(R.string.neutral_ind_pi_gap)
}

private fun indicatorExplain(id: IndicatorId): Int = when (id) {
    IndicatorId.MAYER -> R.string.neutral_explain_mayer
    IndicatorId.MA200W -> R.string.neutral_explain_ma200w
    IndicatorId.ATH_DISTANCE -> R.string.neutral_explain_ath_distance
    IndicatorId.CHANGE_30D -> R.string.neutral_explain_change_30d
    IndicatorId.SMA50_VS_200 -> R.string.neutral_explain_sma50_200
    IndicatorId.RSI_WEEKLY -> R.string.neutral_explain_rsi_weekly
    IndicatorId.RSI_DAILY -> R.string.neutral_explain_rsi_daily
    IndicatorId.VS_BTC -> R.string.neutral_explain_vs_btc
    IndicatorId.MVRV -> R.string.neutral_explain_mvrv
    IndicatorId.PUELL -> R.string.neutral_explain_puell
    IndicatorId.HASHRATE_30_60 -> R.string.neutral_explain_hashrate
    IndicatorId.PI_CYCLE_GAP -> R.string.neutral_explain_pi_gap
}

/**
 * «hoch heisst …, tief heisst …» je Kennzahl — was ein hoher und ein tiefer Wert
 * beschreibt, ohne Kauf- oder Verkaufssignal. Wie `IndicatorText.reading` (iOS).
 */
private fun indicatorReading(id: IndicatorId): Int = when (id) {
    IndicatorId.MAYER -> R.string.ind_read_mayer
    IndicatorId.MA200W -> R.string.ind_read_ma200w
    IndicatorId.ATH_DISTANCE -> R.string.ind_read_ath_distance
    IndicatorId.CHANGE_30D -> R.string.ind_read_change_30d
    IndicatorId.SMA50_VS_200 -> R.string.ind_read_sma50_vs_200
    IndicatorId.RSI_WEEKLY -> R.string.ind_read_rsi_weekly
    IndicatorId.RSI_DAILY -> R.string.ind_read_rsi_daily
    IndicatorId.VS_BTC -> R.string.ind_read_vs_btc
    IndicatorId.MVRV -> R.string.ind_read_mvrv
    IndicatorId.PUELL -> R.string.ind_read_puell
    IndicatorId.HASHRATE_30_60 -> R.string.ind_read_hashrate_30_60
    IndicatorId.PI_CYCLE_GAP -> R.string.ind_read_pi_cycle_gap
}

/** Wert einer Kennzahl: Faktoren «1.12×», Prozente «+5.2%», RSI «48», 200-Wochen-Schnitt als Kurs. */
internal fun indicatorValue(indicator: Indicator, currency: String): String {
    val v = indicator.value ?: return "—"
    return when (indicator.id) {
        IndicatorId.MAYER, IndicatorId.MVRV, IndicatorId.PUELL -> LocaleNumbers.decimal(v, 2) + "×"
        IndicatorId.MA200W -> PriceFormat.priceWithCurrency(v, currency)
        IndicatorId.RSI_WEEKLY, IndicatorId.RSI_DAILY -> LocaleNumbers.integer(Math.round(v))
        // Abstand unter dem Hoch als negative Zahl («−23.4%»), 0 ohne Vorzeichen
        IndicatorId.ATH_DISTANCE -> PriceFormat.signedPercent(-v, 1)
        IndicatorId.CHANGE_30D, IndicatorId.SMA50_VS_200, IndicatorId.VS_BTC, IndicatorId.HASHRATE_30_60,
        IndicatorId.PI_CYCLE_GAP -> PriceFormat.signedPercent(v, 1)
    }
}
