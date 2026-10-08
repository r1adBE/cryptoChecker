package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CycleInfo
import com.cryptochecker.app.domain.market.CycleReport
import com.cryptochecker.app.domain.market.CycleSignal
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.MarketZone
import com.cryptochecker.app.domain.market.SignalId
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.theme.MarketScaleColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.displayCompact
import com.cryptochecker.app.util.LocaleNumbers
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Farbe je Marktzone: die Stufen der gemeinsamen Skala [MarketScaleColors] (Reihenfolge der Zonen). */
internal val ZoneColors: Map<MarketZone, Color> = MarketZone.entries.associateWith { MarketScaleColors.steps[it.ordinal] }

/**
 * Marktphase nach dem Zonenmodell als Zeile unter «Einordnung»: wie viele historische Top-
 * bzw. Bottom-Signale treffen gerade gleichzeitig zu? Rechts die Zone, darunter die
 * Kurzdeutung; Tippen klappt Skala, Scores, Indikatoren, Zyklus und Quelle auf.
 */
@Composable
internal fun MarketPhaseRow(
    cycle: CycleInfo,
    state: MarketState,
    onRetry: () -> Unit,
    divider: Boolean = true,
    /** Herkunft (Kerzen-Anbieter, Coin Metrics) und Stand für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showScoreInfo by remember { mutableStateOf(false) }

    if (showScoreInfo) {
        AlertDialog(
            onDismissRequest = { showScoreInfo = false },
            title = { Text(stringResource(R.string.market_scores_info_title)) },
            text = {
                Text(
                    text = stringResource(R.string.market_scores_info_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showScoreInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    val report = (state as? MarketState.Loaded)?.report
    MarketRow(
        title = stringResource(R.string.market_phase_title),
        secondary = report?.let { stringResource(zoneHint(it.zone)) }.orEmpty(),
        value = report?.let { stringResource(zoneLabel(it.zone)) },
        divider = divider,
        loading = state is MarketState.Loading,
        failure = if (state is MarketState.Failed) stringResource(R.string.market_phase_trend_failed) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (report != null) MarketPhaseDetails(report, onScoreInfo = { showScoreInfo = true })
        // Zyklus nach Kalender — auch ohne Internet. Das nächste Halving steht in der
        // Halving-Zeile und wird hier nicht wiederholt.
        val dateFormat = remember { LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
        Text(
            text = pluralStringResource(
                R.plurals.market_phase_cycle_since,
                cycle.monthsSinceHalving.toInt(),
                cycle.monthsSinceHalving.toInt(),
                cycle.lastHalving.format(dateFormat)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        Text(
            text = stringResource(R.string.market_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Text(
            text = stringResource(R.string.market_phase_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Aufgeklappte Marktphase (früher in der Karte): Index, Skala, die beiden Hinweis-Pillen mit
 * ⓘ, Kurzdeutung der Scores, Hinweis ohne On-Chain-Daten und die Indikatoren zum Aufklappen.
 */
@Composable
private fun MarketPhaseDetails(report: CycleReport, onScoreInfo: () -> Unit) {
    var showIndicators by rememberSaveable { mutableStateOf(false) }
    // Index gross; für den Screenreader steht er schon in der Beschreibung der Skala (a11y_gauge)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clearAndSetSemantics { }) {
        Text(
            text = LocaleNumbers.integer(report.index),
            style = MaterialTheme.typography.displayCompact.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            text = "/ " + LocaleNumbers.integer(100),
            style = MaterialTheme.typography.bodyMedium.amountNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).alignByBaseline()
        )
    }

    ZoneGauge(index = report.index, modifier = Modifier.padding(top = Spacing.sm))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        // Zwei Hinweis-Pillen; ab 5/10 leicht in der Farbe des passenden Skalenendes
        SignalPill(
            text = stringResource(R.string.market_signal_top, report.topScore),
            tint = ZoneColors.getValue(MarketZone.EXTREME_BULL).takeIf { report.topScore >= 5 },
            modifier = Modifier.weight(1f)
        )
        SignalPill(
            text = stringResource(R.string.market_signal_bottom, report.bottomScore),
            tint = ZoneColors.getValue(MarketZone.EXTREME_BEAR).takeIf { report.bottomScore >= 5 },
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        IconButton(onClick = onScoreInfo) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.market_scores_info_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Kurzdeutung der beiden Zahlen
    Text(
        text = stringResource(
            if (report.topScore == 0 && report.bottomScore == 0) R.string.market_scores_none
            else R.string.market_scores_hint
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!report.onChainAvailable) {
        Text(
            text = stringResource(R.string.market_onchain_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }

    TextButton(
        onClick = { showIndicators = !showIndicators },
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Text(
            stringResource(
                if (showIndicators) R.string.market_hide_indicators else R.string.market_show_indicators
            )
        )
    }

    if (showIndicators) {
        report.signals.forEach { SignalRow(it) }
    }
}

/** Platzhalter in der Form von [ZoneGauge]: Skala, Zonen-Beschriftung, Zeile darunter. */
@Composable
internal fun ZoneGaugeSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            SkeletonBlock(Modifier.align(Alignment.Center).fillMaxWidth().height(8.dp))
        }
        SkeletonLine(MaterialTheme.typography.labelSmall, Modifier.padding(top = 4.dp).fillMaxWidth())
        SkeletonLine(MaterialTheme.typography.labelSmall, Modifier.fillMaxWidth(0.7f))
    }
}

/** Skala von Extrem Bear (links) bis Extrem Bull (rechts) mit Markierung. */
@Composable
internal fun ZoneGauge(index: Int, modifier: Modifier = Modifier) {
    // Screenreader: «Skala von Extreme Bear bis Extreme Bull: 63 von 100»
    val description = stringResource(
        R.string.a11y_gauge,
        stringResource(R.string.zone_extreme_bear),
        stringResource(R.string.zone_extreme_bull),
        index
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = description }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    // Skala folgt der Leserichtung wie die Beschriftung darunter: Verlauf (absolut
                    // gezeichnet) bei RTL umgedreht, die Markierung (offset) spiegelt sich selbst
                    .background(Brush.horizontalGradient(ZoneColors.values.toList().let { if (rtl) it.reversed() else it }))
            )
            val markerSize = 18.dp
            Box(
                modifier = Modifier
                    .offset(x = (maxWidth - markerSize) * (index / 100f))
                    .size(markerSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }
        // Zwischenmarken: Bear · Neutral · Bull, an den Enden die Extremzonen
        // (für den Screenreader in der Beschreibung der Skala enthalten)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            listOf(
                R.string.zone_extreme_bear,
                R.string.zone_bear,
                R.string.zone_neutral,
                R.string.zone_bull,
                R.string.zone_extreme_bull,
            ).forEachIndexed { index, label ->
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Rechts heisst nicht «gut»: dort liegt das Top-Risiko.
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.zone_bottom_chance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f)
            )
            Text(
                "▲ " + stringResource(R.string.zone_top_risk),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Tonale graue Pille; mit [tint] leicht (14 %) in der Zonenfarbe getönt, Schrift bleibt onSurface. */
@Composable
private fun SignalPill(text: String, tint: Color?, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
    )
}

@Composable
private fun SignalRow(signal: CycleSignal) {
    val name = when (signal.id) {
        SignalId.MVRV_NUPL -> "MVRV"
        SignalId.PUELL -> "Puell Multiple"
        SignalId.MAYER -> "Mayer Multiple"
        SignalId.MA200W -> stringResource(R.string.ind_ma200w)
        SignalId.DRAWDOWN -> stringResource(R.string.ind_drawdown)
        SignalId.HASH_RIBBON -> stringResource(R.string.ind_hash)
        SignalId.PARABOLIC -> stringResource(R.string.ind_parabolic)
        SignalId.HALVING_TIME -> stringResource(R.string.ind_halving)
        SignalId.ATH_TIME -> stringResource(R.string.ind_ath)
    }
    val points = when {
        signal.topPoints > 0 -> stringResource(R.string.ind_points_top, signal.topPoints) to ZoneColors.getValue(MarketZone.BULL)
        signal.bottomPoints > 0 -> stringResource(R.string.ind_points_bottom, signal.bottomPoints) to ZoneColors.getValue(MarketZone.BEAR)
        else -> "–" to MaterialTheme.colorScheme.outline
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            signal.value?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = points.first,
            style = MaterialTheme.typography.labelMedium,
            color = points.second
        )
    }
}

internal fun zoneTextColor(zone: MarketZone): Color = MarketScaleColors.onStep(zone.ordinal)

internal fun zoneLabel(zone: MarketZone): Int = when (zone) {
    MarketZone.EXTREME_BEAR -> R.string.zone_extreme_bear
    MarketZone.BEAR -> R.string.zone_bear
    MarketZone.NEUTRAL -> R.string.zone_neutral
    MarketZone.BULL -> R.string.zone_bull
    MarketZone.EXTREME_BULL -> R.string.zone_extreme_bull
}

internal fun zoneHint(zone: MarketZone): Int = when (zone) {
    MarketZone.EXTREME_BEAR -> R.string.zone_hint_extreme_bear
    MarketZone.BEAR -> R.string.zone_hint_bear
    MarketZone.NEUTRAL -> R.string.zone_hint_neutral
    MarketZone.BULL -> R.string.zone_hint_bull
    MarketZone.EXTREME_BULL -> R.string.zone_hint_extreme_bull
}
