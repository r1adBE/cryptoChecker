package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.HalvingEstimate
import com.cryptochecker.app.domain.market.HalvingInfo
import com.cryptochecker.app.util.LocaleNumbers
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ───────────────────────── Halving & Zyklus-Vergleich ─────────────────────────

/**
 * Halving als Zeile unter «Einordnung»: rechts der geschätzte Monat des nächsten Halvings
 * («~Apr. 2028»), darunter das Datum des letzten. Bewusst ohne Fortschrittsbalken, Prozent
 * oder Countdown in Tagen: Die App kennt die Blockhöhe nicht, der Termin ist nur geschätzt,
 * und ein Zyklus-Fortschritt wäre eine Deutung. Tippen klappt den Zyklus-Chart auf
 * (Kursverlauf vergangener Zyklen, ausdrücklich keine Prognose).
 */
@Composable
internal fun HalvingRow(
    halving: HalvingInfo,
    history: LoadState<CycleHistory>,
    onRetry: () -> Unit,
    first: Boolean = false,
    /** Rechnerisch aus Blockhöhe und Blockzeit ([HalvingEstimate]); null = nur Kalender. */
    estimate: HalvingEstimate? = null,
) {
    val dateFormat = remember { LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    val monthFormat = remember { LocaleNumbers.dates(DateTimeFormatter.ofPattern("MMM yyyy")) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val nextMonth = "~" + (estimate?.date ?: halving.nextHalvingEstimate).format(monthFormat)

    MarketRow(
        title = stringResource(R.string.insights_halving_title),
        secondary = stringResource(R.string.neutral_halving_last, halving.lastHalving.format(dateFormat)),
        value = nextMonth,
        first = first,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        info = MarketInfoTopic.HALVING,
    ) {
        Text(
            text = if (estimate != null) {
                stringResource(
                    R.string.halving_estimate_text,
                    nextMonth,
                    LocaleNumbers.integer(estimate.remainingBlocks.toLong(), grouping = true),
                    LocaleNumbers.decimal(estimate.avgBlockSeconds / 60.0, 1),
                )
            } else {
                stringResource(R.string.neutral_halving_next, nextMonth)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(stringResource(R.string.insights_cycle_chart), style = MaterialTheme.typography.labelLarge)
        CardSwap(history, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> CycleChartSkeleton()
                LoadState.Failed -> UnavailableRow(onRetry)
                is LoadState.Loaded -> CycleChart(shown.value, currentHalving = halving.lastHalving)
            }
        }
    }
}
