package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleInfo
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

// ───────────────────────── Halving & Zyklus-Vergleich ─────────────────────────

/**
 * Halving als Zeile unter «Einordnung»: rechts das geschätzte Datum des nächsten Halvings,
 * darunter der Fortschritt im Zyklus; Tippen klappt Countdown, Balken und Zyklus-Chart auf.
 */
@Composable
internal fun HalvingRow(cycle: CycleInfo, history: LoadState<CycleHistory>, onRetry: () -> Unit, divider: Boolean = true) {
    val today = LocalDate.now()
    val total = ChronoUnit.DAYS.between(cycle.lastHalving, cycle.nextHalvingEstimate).coerceAtLeast(1)
    val elapsed = ChronoUnit.DAYS.between(cycle.lastHalving, today).coerceIn(0, total)
    val remaining = ChronoUnit.DAYS.between(today, cycle.nextHalvingEstimate).coerceAtLeast(0)
    val dateFormat = remember { LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    MarketRow(
        title = stringResource(R.string.insights_halving_title),
        secondary = stringResource(R.string.insights_cycle_progress, (elapsed * 100 / total).toInt()),
        value = cycle.nextHalvingEstimate.format(dateFormat),
        divider = divider,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            text = pluralStringResource(R.plurals.insights_halving_countdown, remaining.toInt(), remaining.toInt(), cycle.nextHalvingEstimate.format(dateFormat)),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { elapsed / total.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = 16.dp).clip(RoundedCornerShape(50))
        )
        Text(stringResource(R.string.insights_cycle_chart), style = MaterialTheme.typography.labelLarge)
        CardSwap(history, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> CycleChartSkeleton()
                LoadState.Failed -> FailedRow(onRetry)
                is LoadState.Loaded -> CycleChart(shown.value, currentHalving = cycle.lastHalving)
            }
        }
    }
}
