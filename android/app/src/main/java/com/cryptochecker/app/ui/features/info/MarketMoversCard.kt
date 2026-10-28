package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.MarketMoversResult
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.segmentLines
import com.cryptochecker.app.ui.features.portfolio.PlPill
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText

/**
 * «Gewinner und Verlierer 24 h» unter dem Pulse: je bis zu fünf Coins mit dem grössten Plus bzw. Minus unter den
 * Top 100 ohne Stablecoins ([com.cryptochecker.app.domain.exchange.MarketMovers], dieselbe CoinGecko-Antwort wie die
 * Marktbreite). Solange der Pulse lädt, ein form-gleicher Platzhalter ([MoversSkeleton]) — darunter springt nichts,
 * wenn die Zeilen kommen; bei Fehler nichts (den zeigt schon die Pulse-Karte darüber). Je Zeile Logo, Kürzel und Name, «24h» und die Änderung mit Pfeil und Vorzeichen in der Kursfarbe (nie Farbe
 * allein). Tippen ([onOpen]): dasselbe wie in «Heute auffällig» (Aktionsblatt des Paars, sonst seine Vorschau mit
 * «Zur Merkliste»). Screenreader: je Zeile ein Element mit Aktion. Keine Prognose, keine Empfehlung.
 *
 * Auf «Jetzt» ruhig und kompakt: je Gruppe zuerst [MOVERS_COMPACT] Zeilen, «Alle zeigen» klappt auf alle
 * (bis fünf) auf, «Weniger zeigen» wieder zu — gemerkt, solange der Tab lebt (auch über Drehen). Wie iOS `CycleMoversCard`.
 */
@Composable
internal fun MarketMoversCard(state: LoadState<PulseReport>, onOpen: (MoverCoin) -> Unit) {
    if (state is LoadState.Loading) {
        MoversSkeleton()
        return
    }
    val movers = (state as? LoadState.Loaded)?.value?.movers ?: return
    if (movers.isEmpty) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val more = movers.gainers.size > MOVERS_COMPACT || movers.losers.size > MOVERS_COMPACT
    val limit = if (expanded) Int.MAX_VALUE else MOVERS_COMPACT
    InsightCard(stringResource(R.string.movers_title)) {
        MoversList(movers, limit, onOpen)
        if (more) {
            // Klappt nur auf (kein Seitenwechsel): Pfeil nach unten bzw. oben wie bei «Was bewegt sich mit?»
            ExpandToggleButton(
                text = stringResource(if (expanded) R.string.market_movers_show_less else R.string.market_movers_show_all),
                expanded = expanded,
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        SourceText(stringResource(R.string.movers_source))
        CoinGeckoAttribution()
    }
}

/** So viele Zeilen je Gruppe zeigt die kompakte Karte, bevor «Alle zeigen» aufklappt. */
internal const val MOVERS_COMPACT = 3

@Composable
private fun MoversList(movers: MarketMoversResult, limit: Int, onOpen: (MoverCoin) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        MoversGroup(stringResource(R.string.movers_gainers), stringResource(R.string.movers_no_gainers), movers.gainers.take(limit), onOpen)
        MoversGroup(stringResource(R.string.movers_losers), stringResource(R.string.movers_no_losers), movers.losers.take(limit), onOpen)
    }
}

/**
 * Platzhalter beim Laden: dieselbe Karte mit Titel und Unterüberschriften, je Gruppe [MOVERS_COMPACT] Zeilen
 * in der Form von [MoverRow] (Logo, Kürzel, Name, Änderung) — so hoch wie die Karte mit Daten.
 */
@Composable
private fun MoversSkeleton() {
    InsightCard(stringResource(R.string.movers_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            for (title in listOf(R.string.movers_gainers, R.string.movers_losers)) {
                Column {
                    MoversGroupTitle(stringResource(title))
                    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(ListSegment.Gap)) {
                            repeat(MOVERS_COMPACT) { index -> MoverRowSkeleton(ListSegment.shape(index, MOVERS_COMPACT)) }
                        }
                    }
                }
            }
        }
        SourceText(stringResource(R.string.movers_source))
        CoinGeckoAttribution()
    }
}

/** Eine Platzhalter-Zeile mit denselben Abständen und Zeilenhöhen wie [MoverRow]. */
@Composable
private fun MoverRowSkeleton(shape: Shape) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        SkeletonBlock(Modifier.padding(end = Spacing.sm).size(ListSegment.Logo))
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.md)) {
            SkeletonLine(MaterialTheme.typography.titleMedium, Modifier.width(48.dp))
            SkeletonLine(MaterialTheme.typography.bodySmall, Modifier.width(96.dp))
        }
        SkeletonLine(MaterialTheme.typography.labelLarge, Modifier.width(64.dp))
    }
}

/** Unterüberschrift einer Gruppe; für den Screenreader eine Überschrift. */
@Composable
private fun MoversGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.sm).semantics { heading() }
    )
}

/** Unterüberschrift (für den Screenreader eine Überschrift) und die Zeilen als eine Fläche ([ListSegment]). */
@Composable
private fun MoversGroup(title: String, empty: String, coins: List<MoverCoin>, onOpen: (MoverCoin) -> Unit) {
    Column {
        MoversGroupTitle(title)
        if (coins.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ListSegment.Gap)) {
                coins.forEachIndexed { index, coin ->
                    MoverRow(coin, ListSegment.shape(index, coins.size)) { onOpen(coin) }
                }
            }
        }
    }
}

/** Eine Zeile; für den Screenreader ein Element: «Solana (SOL), gestiegen um 7.10%», Aktion «Kurs und Aktionen zeigen». */
@Composable
private fun MoverRow(coin: MoverCoin, shape: Shape, onTap: () -> Unit) {
    val context = LocalContext.current
    val name = coin.name.ifBlank { coin.symbol }
    val spoken = stringResource(R.string.movers_a11y_row, name, coin.symbol, A11yText.change(context, coin.change24h))
    val actionLabel = stringResource(R.string.unusual_action_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .segmentLines(shape, divider = ListSegment.dividerColor())
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = actionLabel) {
                    onTap()
                    true
                }
            }
            // Gleicher Innenabstand und gleich grosses Logo wie in der Merkliste und «Heute auffällig»
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        CoinBadge(coin.symbol, size = ListSegment.Logo, modifier = Modifier.padding(end = Spacing.sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.md)
        ) {
            Text(
                coin.symbol,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (name != coin.symbol) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // «24h ↗ +9.4%»: Zeitraum klein und grau vor der Änderung, wie in der Merkliste
        Text(
            stringResource(R.string.why_change_24h),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = Spacing.xs)
        )
        PlPill(coin.change24h)
    }
}
