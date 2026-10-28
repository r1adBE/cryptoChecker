package com.cryptochecker.app.ui.features.info

import androidx.compose.ui.graphics.Shape
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.segmentLines
import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
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
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.features.portfolio.PlPill
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.A11yText
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * «Heute auffällig»: bis zu fünf Coins (aus den rund 30 grössten), die sich heute
 * ungewöhnlich verhalten — je Zeile Plakette, Symbol, 24-h-Änderung und die auffälligste
 * Tatsache in einem kurzen Satz ([com.cryptochecker.app.domain.exchange.MarketUnusual]).
 * Nichts auffällig: eine ruhige Zeile. Tippen auf eine Zeile ([onOpen]): dasselbe wie in der
 * Merkliste — das Aktionsblatt des Paars, sonst seine Vorschau (siehe MarketPhaseScreen).
 * Screenreader: je Zeile ein Element mit Aktion. Keine Prognose, keine Empfehlung.
 */
@Composable
internal fun UnusualCard(
    state: LoadState<UnusualReport>,
    onOpen: (UnusualRow) -> Unit,
    onRetry: () -> Unit,
) {
    InsightCard(stringResource(R.string.unusual_title), info = MarketInfoTopic.UNUSUAL) {
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
                        // Wie die Merkliste: die Zeilen als eine Fläche mit feinen Trennlinien ([ListSegment])
                        Column(verticalArrangement = Arrangement.spacedBy(ListSegment.Gap)) {
                            rows.forEachIndexed { index, row ->
                                UnusualRowItem(
                                    row,
                                    shape = ListSegment.shape(index, rows.size),
                                    onTap = { onOpen(row) }
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.unusual_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                    CoinGeckoAttribution()
                }
            }
        }
    }
}

/** Eine Zeile; für den Screenreader ein Element: «Solana, gestiegen um 5.2%. Läuft gegen den Markt.» */
@Composable
private fun UnusualRowItem(row: UnusualRow, shape: Shape, onTap: () -> Unit) {
    val context = LocalContext.current
    val factText = unusualFactText(context, row.fact)
    val spoken = stringResource(
        R.string.unusual_a11y_row,
        row.name.ifBlank { row.symbol },
        A11yText.change(context, row.change24h),
        factText.trimEnd('.')
    )
    // Ein Ziel für alle Zeilen: Kurs und Aktionen (wie ein Tipp in der Merkliste)
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
            // Gleicher Innenabstand und gleich grosses Logo wie in der Merkliste
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        CoinBadge(row.symbol, size = ListSegment.Logo, modifier = Modifier.padding(end = 8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                row.symbol,
                style = MaterialTheme.typography.titleMedium,
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
        // «24h ↗ +9.4%»: Zeitraum klein und grau vor der Änderung, wie in der Merkliste
        Text(
            stringResource(R.string.why_change_24h),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = 4.dp)
        )
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
        String.format(com.cryptochecker.app.util.LocaleNumbers.appLocale, "%.1f", fact.volumeRatio ?: 0.0)
    )
    UnusualFactKind.FUNDING_HIGH ->
        context.getString(R.string.unusual_fact_funding_high, ActivityTexts.percent(fact.fundingPercent ?: 0.0, 3))
    UnusualFactKind.FUNDING_NEGATIVE ->
        context.getString(R.string.unusual_fact_funding_negative, ActivityTexts.percent(fact.fundingPercent ?: 0.0, 3))
}

/** Platzhalter in Zeilenform: drei Zeilen mit Plakette, Symbol, Satz und Änderung. */
@Composable
private fun UnusualSkeleton() {
    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
        repeat(3) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.sm)
            ) {
                SkeletonBlock(Modifier.size(36.dp))
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
 * Kompakte Zeile «Wirtschaftsdaten» — nur, wenn heute (oder in den nächsten 18 h) wichtige
 * US-Daten anstehen bzw. heute veröffentlicht wurden ([MacroCalendar.hint]). Auf «Jetzt» ([atTop],
 * aufgeklappt unter «Mehr Marktdaten») nur bei einem Termin in ±2 h ([MacroCalendar.isImminent]), sonst
 * auf der Unterseite «Daten». Rechnet jede Minute neu (Wechsel des Platzes, «veröffentlicht», Mitternacht).
 * Grau, ohne Bedeutungsfarbe; für den Screenreader ein Element, dazu das Info-Zeichen
 * ([MarketInfoButton]) als eigener Knopf.
 */
@Composable
internal fun MacroHintRow(events: List<MacroEvent>, atTop: Boolean) {
    if (events.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(events) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    val hint = remember(events, now / 60_000L) { MacroCalendar.hint(events, now, ZoneId.systemDefault()) } ?: return
    if (MacroCalendar.isImminent(hint, now) != atTop) return
    val context = LocalContext.current
    val text = MacroTexts.hint(context, hint)
    val title = stringResource(R.string.macro_title)
    // Unter «Daten» eine Karte wie die übrigen (Titel mit Strich in der Karte, nicht aufklappbar)
    if (!atTop) {
        MacroDataCard(title, text)
        return
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            // Auf «Jetzt» unter «Mehr Marktdaten» als Kasten, mit Abstand zur Karte darüber
            .padding(top = Spacing.md)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        // Hinweis als ein Element; das Info-Zeichen rechts bleibt ein eigener Knopf
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = "$title. $text" }
        ) {
            MacroHintText(text)
        }
        MarketInfoButton(MarketInfoTopic.MACRO, Modifier.padding(start = Spacing.xs))
    }
}

/**
 * «Wirtschaftsdaten» unter «Daten»: eine Karte wie die übrigen Einträge (Titel mit Strich in
 * der Karte, darunter der Hinweis), ohne Pfeil — nichts zum Aufklappen; hinter dem Titel das
 * Info-Zeichen. Für den Screenreader ein Element und eine Überschrift, dazu der Info-Knopf.
 */
@Composable
private fun MacroDataCard(title: String, text: String) {
    AppCard(modifier = Modifier.padding(top = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.sm)) {
            // Titel und Hinweis als ein Element (Überschrift); das Info-Zeichen bleibt ein eigener Knopf
            Text(
                text = title,
                style = SectionTitle.style,
                color = SectionTitle.color,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clearAndSetSemantics {
                        contentDescription = "$title. $text"
                        heading()
                    }
                    .sectionTitleMarker()
            )
            MarketInfoButton(MarketInfoTopic.MACRO, Modifier.padding(start = 2.dp))
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.clearAndSetSemantics { }) { MacroHintText(text) }
    }
}

/** Info-Zeichen und Hinweistext (grau, ohne Bedeutungsfarbe) in Lesegrösse ([body]; ganze Sätze mit Platz). */
@Composable
private fun MacroHintText(text: String) {
    Icon(
        painterResource(R.drawable.ic_info),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp).size(16.dp)
    )
    Text(
        text,
        style = MaterialTheme.typography.body,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = Spacing.sm)
    )
}
