package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.ui.theme.AssetColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.delay

// ───────────────────────── Dominanz & Altcoin-Saison ─────────────────────────

/**
 * Bitcoin-Dominanz und Altcoin-Saison als zwei Zeilen unter «Einordnung». Tippen zeigt bei
 * der Dominanz die Anteile als Balken, bei der Altcoin-Saison Balken, Erklärung, Stand mit
 * «Aktualisieren» und die Quelle.
 */
@Composable
internal fun DominanceRows(
    dominance: LoadState<Dominance>,
    altSeason: LoadState<AltSeason>,
    onRetry: () -> Unit,
    /** Zeitpunkt der gezeigten Altcoin-Saison (Zwischenspeicher 3 h); null = noch nichts. */
    altSeasonAsOf: Long? = null,
    altSeasonRefreshing: Boolean = false,
    onRefreshAltSeason: () -> Unit = {},
    /** Herkunft und Stand der Dominanz (CoinGecko) bzw. der Altcoin-Saison (Kerzen-Anbieter). */
    dominanceStamp: DataStamp? = null,
    altSeasonStamp: DataStamp? = null,
) {
    val failed = stringResource(R.string.something_went_wrong)
    val d = (dominance as? LoadState.Loaded)?.value
    var dominanceExpanded by rememberSaveable { mutableStateOf(false) }
    MarketRow(
        title = stringResource(R.string.insights_dominance_title),
        // BTC steht rechts; hier ETH (ohne ETH-Wert bleibt die Zeile leer, gleiche Höhe)
        secondary = d?.eth?.let { stringResource(R.string.insights_dominance_eth, percentOne(it)) }.orEmpty(),
        value = d?.let { percentOne(it.btc) },
        loading = dominance is LoadState.Loading,
        failure = if (dominance is LoadState.Failed) failed else null,
        onRetry = onRetry,
        stamp = dominanceStamp,
        expanded = dominanceExpanded,
        onToggle = if (d != null) ({ dominanceExpanded = !dominanceExpanded }) else null,
    ) {
        if (d != null) {
            // Anteile als Balken: BTC · ETH · übrige
            val eth = (d.eth ?: 0.0).coerceAtLeast(0.0)
            val rest = (100.0 - d.btc - eth).coerceAtLeast(0.0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
            ) {
                Box(Modifier.weight(d.btc.toFloat().coerceAtLeast(0.1f)).height(10.dp).background(AssetColors.bitcoin))
                if (eth > 0) Box(Modifier.weight(eth.toFloat()).height(10.dp).background(AssetColors.ethereum))
                if (rest > 0) Box(Modifier.weight(rest.toFloat()).height(10.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            SourceText(stringResource(R.string.insights_source_dominance))
        }
    }

    val a = (altSeason as? LoadState.Loaded)?.value
    var altExpanded by rememberSaveable { mutableStateOf(false) }
    val altLabel = a?.let {
        stringResource(
            when {
                it.index >= 75 -> R.string.altseason_alt
                it.index <= 25 -> R.string.altseason_btc
                else -> R.string.altseason_mixed
            }
        )
    }.orEmpty()
    MarketRow(
        title = stringResource(R.string.insights_altseason_title),
        // Anbieter und Alter («… · Binance · heute 14:05») hängt MarketRow aus dem Stand an
        secondary = altLabel,
        value = a?.let { LocaleNumbers.integer(it.index) },
        loading = altSeason is LoadState.Loading,
        failure = if (altSeason is LoadState.Failed) failed else null,
        onRetry = onRetry,
        stamp = altSeasonStamp,
        expanded = altExpanded,
        onToggle = if (a != null) ({ altExpanded = !altExpanded }) else null,
    ) {
        if (a != null) {
            LinearProgressIndicator(
                progress = { a.index / 100f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            )
            Text(
                pluralStringResource(R.plurals.insights_altseason_value, a.outperformers, a.outperformers, a.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            if (altSeasonAsOf != null) {
                AltSeasonAsOfRow(altSeasonAsOf, altSeasonRefreshing, onRefreshAltSeason)
            }
            SourceText(stringResource(R.string.insights_source_dominance))
        }
    }
}

/** «58.4 %» — eine Nachkommastelle, in den Ziffern der App-Sprache. */
private fun percentOne(value: Double): String = LocaleNumbers.decimal(value, 1) + " %"

/**
 * «Stand 14:05» und ein kleines «Aktualisieren» unter der Altcoin-Saison. Der Knopf ist erst
 * 5 Min. nach dem letzten Stand wieder aktiv ([CycleCachePolicy.MANUAL_MIN_INTERVAL_MILLIS]),
 * sonst gilt der 3-h-Zwischenspeicher.
 */
@Composable
private fun AltSeasonAsOfRow(asOf: Long, refreshing: Boolean, onRefresh: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(asOf) {
        while (true) {
            now = System.currentTimeMillis()
            if (CycleCachePolicy.canManualRefresh(asOf, now, CycleCachePolicy.MANUAL_MIN_INTERVAL_MILLIS)) break
            delay(15_000L)
        }
    }
    val enabled = !refreshing &&
        CycleCachePolicy.canManualRefresh(asOf, now, CycleCachePolicy.MANUAL_MIN_INTERVAL_MILLIS)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.pulse_updated, PriceFormat.time(asOf)),
            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRefresh, enabled = enabled) {
            Text(stringResource(R.string.action_refresh), style = MaterialTheme.typography.labelMedium)
        }
    }
}
