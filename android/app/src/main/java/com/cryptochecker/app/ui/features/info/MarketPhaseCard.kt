package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CycleInfo
import com.cryptochecker.app.domain.market.CycleSignal
import com.cryptochecker.app.domain.market.MarketZone
import com.cryptochecker.app.domain.market.SignalId
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPill
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.SkeletonText
import com.cryptochecker.app.ui.theme.amountNumbers
import java.time.format.DateTimeFormatter

internal val ZoneColors = mapOf(
    MarketZone.EXTREME_BEAR to Color(0xFFB42318),
    MarketZone.BEAR to Color(0xFFE5484D),
    MarketZone.NEUTRAL to Color(0xFF7A7A7A),
    MarketZone.BULL to Color(0xFF2FA36B),
    MarketZone.EXTREME_BULL to Color(0xFF0B7A45),
)

/**
 * Marktphase nach dem Zonenmodell: Wie viele historische Top- bzw.
 * Bottom-Signale treffen gerade gleichzeitig zu? Dazu Halving-Zeit und Trend.
 */
@Composable
internal fun MarketPhaseCard(cycle: CycleInfo, state: MarketState, onRetry: () -> Unit) {
    // Datum in der Schreibweise der gewählten Sprache
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM) }
    var showDetails by remember { mutableStateOf(false) }
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

    // Leichter Schein in der Zonenfarbe hinter der Karte
    val glow = (state as? MarketState.Loaded)?.let { ZoneColors.getValue(it.report.zone) }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glow != null) Modifier.background(
                        Brush.verticalGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent))
                    ) else Modifier
                )
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.market_phase_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            CardSwap(state, { marketKey(it) }) { shown ->
                when (shown) {
                    MarketState.Loading -> MarketPhaseSkeleton()

                    MarketState.Failed -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.market_phase_trend_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }

                    is MarketState.Loaded -> {
                        val report = shown.report
                        val zoneColor = ZoneColors.getValue(report.zone)

                        // Zone als großes Etikett, rechts daneben der Index gross; darunter die Kurzdeutung
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(zoneLabel(report.zone)),
                                style = MaterialTheme.typography.headlineSmall,
                                // Weiss nur auf den dunklen Randzonen, sonst dunkle Schrift (Kontrast)
                                color = zoneTextColor(report.zone),
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(RoundedCornerShape(50))
                                    .background(zoneColor)
                                    .padding(horizontal = 18.dp, vertical = 6.dp)
                            )
                            // Screenreader: der Index steht schon in der Beschreibung der Skala (a11y_gauge)
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics { }
                            ) {
                                Text(
                                    text = report.index.toString(),
                                    style = MaterialTheme.typography.headlineMedium.amountNumbers(),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.alignByBaseline()
                                )
                                Text(
                                    text = "/ 100",
                                    style = MaterialTheme.typography.bodyMedium.amountNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp).alignByBaseline()
                                )
                            }
                        }
                        Text(
                            text = stringResource(zoneHint(report.zone)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        ZoneGauge(index = report.index, modifier = Modifier.padding(top = 14.dp))

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
                            IconButton(onClick = { showScoreInfo = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_info),
                                    contentDescription = stringResource(R.string.market_scores_info_title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Kurzdeutung der beiden Zahlen, immer sichtbar
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
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        TextButton(
                            onClick = { showDetails = !showDetails },
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (showDetails) R.string.market_hide_indicators else R.string.market_show_indicators
                                )
                            )
                        }

                        if (showDetails) {
                            report.signals.forEach { SignalRow(it) }
                        }
                    }
                }
            }

            // Zyklus nach Kalender — immer sichtbar, auch ohne Internet. Das nächste
            // Halving steht in der Halving-Karte und wird hier nicht wiederholt.
            Text(
                text = pluralStringResource(
                    R.plurals.market_phase_cycle_since,
                    cycle.monthsSinceHalving.toInt(),
                    cycle.monthsSinceHalving.toInt(),
                    cycle.lastHalving.format(dateFormat)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = stringResource(R.string.market_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = stringResource(R.string.market_phase_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Platzhalter in der Form der geladenen Karte: Zonen-Etikett und Index, Kurzdeutung,
 * Skala, zwei Hinweis-Pillen, Erklärung und Platz des Knopfs «Indikatoren». Höhen aus
 * der echten Typografie, damit die Karte nicht wächst, wenn die Daten spät kommen.
 * Für Screenreader eine Zeile «wird geladen».
 */
@Composable
private fun MarketPhaseSkeleton() {
    val loading = stringResource(R.string.market_phase_trend_loading)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = loading }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            SkeletonPill(
                MaterialTheme.typography.headlineSmall,
                Modifier.width(132.dp),
                horizontal = 18.dp,
                vertical = 6.dp
            )
            SkeletonLine(
                MaterialTheme.typography.headlineMedium.amountNumbers(),
                Modifier.padding(start = 12.dp).width(84.dp)
            )
        }
        // Kurzdeutung: typische Länge (Zone «Bear»)
        SkeletonText(stringResource(R.string.zone_hint_bear), MaterialTheme.typography.bodyMedium, Modifier.padding(top = 8.dp))
        ZoneGaugeSkeleton(Modifier.padding(top = 14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            SkeletonPill(MaterialTheme.typography.labelLarge, Modifier.weight(1f))
            SkeletonPill(MaterialTheme.typography.labelLarge, Modifier.weight(1f).padding(start = 8.dp))
            // Platz des ⓘ-Knopfs (48 dp)
            Spacer(modifier = Modifier.size(48.dp))
        }
        SkeletonText(stringResource(R.string.market_scores_hint), MaterialTheme.typography.bodySmall)
        // Platz des Knopfs «Indikatoren zeigen» (Textknopf samt Mindest-Tippfläche 48 dp)
        Spacer(modifier = Modifier.padding(top = 2.dp).height(48.dp))
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

/** Art des Zustands — nur ein Wechsel der Art wird überblendet (siehe [CardSwap]). */
private fun marketKey(state: MarketState): Int = when (state) {
    MarketState.Loading -> 0
    is MarketState.Loaded -> 1
    MarketState.Failed -> 2
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
                    .background(Brush.horizontalGradient(ZoneColors.values.toList()))
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
            .padding(horizontal = 10.dp, vertical = 6.dp)
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

internal fun zoneTextColor(zone: MarketZone): Color = when (zone) {
    MarketZone.EXTREME_BEAR, MarketZone.EXTREME_BULL -> Color.White
    else -> Color(0xFF111111)
}

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
