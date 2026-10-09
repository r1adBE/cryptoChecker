@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers

/** Funding Rate, nächste Zahlung und Open Interest eines Perpetuals. */
@Composable
internal fun FuturesSection(info: FuturesInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        // ⓘ blendet die Erklärungen zu Funding und Open Interest ein
        var showExplain by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.futures_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showExplain = !showExplain }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.explain_show),
                    tint = if (showExplain) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showExplain) {
            Text(
                stringResource(R.string.futures_funding) + ": " + stringResource(R.string.explain_funding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                stringResource(R.string.futures_open_interest) + ": " + stringResource(R.string.explain_open_interest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.futures_funding),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val rate = info.fundingRatePercent
                Text(
                    text = rate?.let { "%+.4f %%".format(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                    // Positiv: Longs zahlen an Shorts (Markt überhitzt eher), negativ umgekehrt
                    color = if (rate == null) MaterialTheme.colorScheme.onSurface else PriceColors.forChange(rate)
                )
                info.nextFundingTime?.let { next ->
                    val minutes = ((next - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                    Text(
                        stringResource(R.string.futures_next_funding, (minutes / 60).toInt(), (minutes % 60).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.futures_open_interest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = info.openInterestUsd?.let { "$" + compactNumber(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.tabularNumbers()
                )
            }
        }
        Text(
            stringResource(R.string.futures_source, info.source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}

/** Quote-Währungen, deren Kurs als USDT-Preis übernommen werden kann. */
internal val USD_LIKE_QUOTES = setOf("USDT", "USD", "USDC", "FDUSD")

private fun compactNumber(value: Double): String = when {
    value >= 1e9 -> "%.2f B".format(value / 1e9)
    value >= 1e6 -> "%.1f M".format(value / 1e6)
    value >= 1e3 -> "%.1f K".format(value / 1e3)
    else -> "%.0f".format(value)
}
