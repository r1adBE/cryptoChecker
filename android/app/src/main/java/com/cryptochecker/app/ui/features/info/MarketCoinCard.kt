package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CoinReport
import com.cryptochecker.app.domain.market.CoinSignal
import com.cryptochecker.app.domain.market.CoinSignalId
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.MarketZone
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPill
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.SkeletonText
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat

// ───────────────────────── Coin-Analyse ─────────────────────────

/**
 * Coin-Analyse als letzte Zeile unter «Daten»: rechts die Zone des gewählten Coins, darunter
 * Coin und Kurs. Tippen klappt Auswahl und Analyse auf — auch beim Laden und bei Fehler,
 * damit sich ein anderer Coin wählen lässt.
 */
@Composable
internal fun CoinRow(
    coins: List<String>,
    selected: String,
    favorites: Set<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    state: LoadState<CoinReport>,
    onRetry: () -> Unit,
    divider: Boolean = true,
    /** Herkunft und Stand des gezeigten Coins (Kerzen-Anbieter) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val report = (state as? LoadState.Loaded)?.value
    MarketRow(
        title = stringResource(R.string.coin_title),
        secondary = report?.let { "${it.symbol} · ${PriceFormat.priceWithCurrency(it.price, "USDT")}" } ?: selected,
        value = report?.let { stringResource(zoneLabel(it.zone)) },
        divider = divider,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.coin_no_data) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        ComboBox(
            modifier = Modifier.fillMaxWidth(),
            itemList = coins,
            selectedIndex = coins.indexOf(selected),
            label = stringResource(R.string.coin_choose),
            searchable = true,
            emptyText = selected,
            favorites = favorites,
            onToggleFavorite = onToggleFavorite,
            onValueChange = { index -> onSelect(coins[index]) }
        )
        CardSwap(state, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> CoinReportSkeleton()
                // Meldung und «Erneut» stehen schon in der Zeile
                LoadState.Failed -> Unit
                is LoadState.Loaded -> CoinReportContent(shown.value)
            }
        }
    }
}

@Composable
private fun CoinReportContent(report: CoinReport) {
    val zoneColor = ZoneColors.getValue(report.zone)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(zoneLabel(report.zone)),
            style = MaterialTheme.typography.titleLarge,
            color = zoneTextColor(report.zone),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(zoneColor)
                .padding(horizontal = 16.dp, vertical = Spacing.xs)
        )
        Text(
            text = PriceFormat.priceWithCurrency(report.price, "USDT"),
            style = MaterialTheme.typography.titleMedium.tabularNumbers(),
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }

    ZoneGauge(index = report.index, modifier = Modifier.padding(top = Spacing.md))

    Text(
        text = stringResource(R.string.market_scores, report.topScore, report.bottomScore),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = Spacing.sm)
    )

    report.signals.forEach { CoinSignalRow(it) }

    if (report.historyDays in 1 until 1400) {
        Text(
            pluralStringResource(R.plurals.coin_short_history, report.historyDays, report.historyDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    Text(
        stringResource(R.string.coin_price_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Kurze Erklärung per ⓘ (RSI und Pi Cycle); null = ohne Knopf. */
private fun coinExplainRes(id: CoinSignalId): Int? = when (id) {
    CoinSignalId.RSI_WEEKLY, CoinSignalId.RSI_DAILY -> R.string.explain_rsi
    CoinSignalId.PI_CYCLE -> R.string.explain_pi_cycle
    else -> null
}

/**
 * Platzhalter in der Form von [CoinReportContent]: Zonen-Etikett und Kurs, Skala, Scores,
 * je Signal eine Zeile (mit Platz für ⓘ, wo es ihn gibt); der feste Hinweis steht echt da.
 */
@Composable
private fun CoinReportSkeleton() {
    val loading = stringResource(R.string.loading_hint)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            SkeletonPill(MaterialTheme.typography.titleLarge, Modifier.width(110.dp), horizontal = Spacing.lg, vertical = Spacing.xs)
            Spacer(modifier = Modifier.weight(1f))
            SkeletonLine(MaterialTheme.typography.titleMedium.tabularNumbers(), Modifier.width(100.dp))
        }
        ZoneGaugeSkeleton(Modifier.padding(top = Spacing.md))
        SkeletonText(
            stringResource(R.string.market_scores, 0, 0),
            MaterialTheme.typography.bodyMedium,
            Modifier.padding(top = Spacing.sm)
        )
        CoinSignalId.entries.forEach { id ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkeletonLine(MaterialTheme.typography.bodyMedium, Modifier.width(120.dp))
                        // Gleiche Höhe wie der ⓘ-Knopf (28 dp) neben dem Namen
                        if (coinExplainRes(id) != null) Spacer(modifier = Modifier.size(28.dp))
                    }
                    SkeletonLine(MaterialTheme.typography.bodySmall.tabularNumbers(), Modifier.width(64.dp))
                }
                SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(40.dp))
            }
        }
    }
    Text(
        stringResource(R.string.coin_price_only),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun CoinSignalRow(signal: CoinSignal) {
    val name = when (signal.id) {
        CoinSignalId.MAYER -> "Mayer Multiple"
        CoinSignalId.MA200W -> stringResource(R.string.ind_ma200w)
        CoinSignalId.DRAWDOWN -> stringResource(R.string.ind_drawdown)
        CoinSignalId.RSI_WEEKLY -> stringResource(R.string.ind_rsi_weekly)
        CoinSignalId.RSI_DAILY -> stringResource(R.string.ind_rsi_daily)
        CoinSignalId.PI_CYCLE -> stringResource(R.string.ind_pi_cycle)
        CoinSignalId.PARABOLIC -> stringResource(R.string.ind_parabolic)
        CoinSignalId.CROSS -> stringResource(R.string.ind_cross)
        CoinSignalId.VS_BTC -> stringResource(R.string.ind_vs_btc)
    }
    val (points, color) = when {
        signal.topPoints > 0 -> stringResource(R.string.ind_points_top, signal.topPoints) to ZoneColors.getValue(MarketZone.BULL)
        signal.bottomPoints > 0 -> stringResource(R.string.ind_points_bottom, signal.bottomPoints) to ZoneColors.getValue(MarketZone.BEAR)
        else -> "–" to MaterialTheme.colorScheme.outline
    }
    val explainRes = coinExplainRes(signal.id)
    var showExplain by remember(signal.id) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                if (explainRes != null) {
                    IconButton(onClick = { showExplain = !showExplain }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_info),
                            contentDescription = stringResource(R.string.explain_show),
                            tint = if (showExplain) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Text(
                signal.value ?: stringResource(R.string.ind_missing),
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (explainRes != null && showExplain) {
                Text(
                    stringResource(explainRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(points, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
