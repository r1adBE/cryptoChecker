package com.cryptochecker.app.ui.features.info

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.UnusualFact
import com.cryptochecker.app.domain.market.UnusualFactKind
import com.cryptochecker.app.domain.market.UnusualReport
import com.cryptochecker.app.domain.market.UnusualRow
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.notification.MacroTexts
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.features.portfolio.CoinBadge
import com.cryptochecker.app.ui.features.portfolio.PlPill
import com.cryptochecker.app.util.A11yText
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

/**
 * «Heute auffällig»: bis zu fünf Coins (aus den rund 30 grössten), die sich heute
 * ungewöhnlich verhalten — je Zeile Plakette, Symbol, 24-h-Pille und die auffälligste
 * Tatsache in einem kurzen Satz ([com.cryptochecker.app.domain.market.MarketUnusual]).
 * Nichts auffällig: eine ruhige Zeile. Tippen auf eine Zeile ([onOpen]): «Warum?» für
 * beobachtete Coins, sonst die Suche im Hinzufügen-Tab (siehe MarketPhaseScreen).
 * Screenreader: je Zeile ein Element mit Aktion. Keine Prognose, keine Empfehlung.
 */
@Composable
internal fun UnusualCard(
    state: LoadState<UnusualReport>,
    isWatched: (String) -> Boolean,
    onOpen: (UnusualRow) -> Unit,
    onRetry: () -> Unit,
) {
    InsightCard(stringResource(R.string.unusual_title)) {
        CardSwap(state, { loadKey(it) }) { shown ->
            when (shown) {
                LoadState.Loading -> UnusualSkeleton()
                LoadState.Failed -> UnavailableRow(onRetry)
                is LoadState.Loaded -> {
                    val rows = shown.value.rows
                    if (rows.isEmpty()) {
                        Text(
                            stringResource(R.string.unusual_nothing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            rows.forEach { row ->
                                UnusualRowItem(row, watched = isWatched(row.symbol), onTap = { onOpen(row) })
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.unusual_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
    }
}

/** Eine Zeile; für den Screenreader ein Element: «Solana, gestiegen um 5.2%. Läuft gegen den Markt.» */
@Composable
private fun UnusualRowItem(row: UnusualRow, watched: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val factText = unusualFactText(context, row.fact)
    val spoken = stringResource(
        R.string.unusual_a11y_row,
        row.name.ifBlank { row.symbol },
        A11yText.change(context, row.change24h),
        factText.trimEnd('.')
    )
    val actionLabel = stringResource(if (watched) R.string.unusual_action_why else R.string.unusual_action_add)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = actionLabel) {
                    onTap()
                    true
                }
            }
            .padding(vertical = 6.dp)
    ) {
        CoinBadge(row.symbol, size = 36.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                row.symbol,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                factText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        PlPill(row.change24h)
    }
}

/** Kurzer Satz zur Tatsache, z. B. «Volumen 2.4× üblich», «Funding hoch (+0.062%)». */
internal fun unusualFactText(context: Context, fact: UnusualFact): String = when (fact.kind) {
    UnusualFactKind.STRONGER_THAN_BTC -> context.getString(R.string.unusual_fact_stronger)
    UnusualFactKind.WEAKER_THAN_BTC -> context.getString(R.string.unusual_fact_weaker)
    UnusualFactKind.AGAINST_MARKET -> context.getString(R.string.unusual_fact_against)
    UnusualFactKind.VOLUME -> context.getString(
        R.string.unusual_fact_volume,
        String.format(Locale.getDefault(), "%.1f", fact.volumeRatio ?: 0.0)
    )
    UnusualFactKind.FUNDING_HIGH ->
        context.getString(R.string.unusual_fact_funding_high, ActivityTexts.percent(fact.fundingPercent ?: 0.0, 3))
    UnusualFactKind.FUNDING_NEGATIVE ->
        context.getString(R.string.unusual_fact_funding_negative, ActivityTexts.percent(fact.fundingPercent ?: 0.0, 3))
}

/** Platzhalter in Zeilenform: drei Zeilen mit Plakette, Symbol, Satz und Pille. */
@Composable
private fun UnusualSkeleton() {
    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
        repeat(3) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    SkeletonLine(MaterialTheme.typography.titleSmall, Modifier.width(48.dp))
                    SkeletonLine(MaterialTheme.typography.bodySmall, Modifier.fillMaxWidth(0.8f))
                }
                SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(64.dp))
            }
        }
    }
}

/**
 * Kompakte Zeile «Wirtschaftsdaten» oben im Abschnitt «Jetzt» — nur, wenn heute (oder in
 * den nächsten 18 h) wichtige US-Daten anstehen bzw. heute veröffentlicht wurden
 * ([MacroCalendar.hint]). Rechnet jede Minute neu (Wechsel zu «veröffentlicht», Mitternacht).
 * Grau, ohne Bedeutungsfarbe; für den Screenreader ein Element.
 */
@Composable
internal fun MacroHintRow(events: List<MacroEvent>) {
    if (events.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(events) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    val hint = remember(events, now / 60_000L) { MacroCalendar.hint(events, now, ZoneId.systemDefault()) } ?: return
    val context = LocalContext.current
    val text = MacroTexts.hint(context, hint)
    val title = stringResource(R.string.macro_title)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clearAndSetSemantics { contentDescription = "$title. $text" }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(16.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}
