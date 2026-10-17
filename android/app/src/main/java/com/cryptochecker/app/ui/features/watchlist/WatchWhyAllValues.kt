// Karte «Alle Werte und Quellen» von «Was bewegt sich mit?» (standardmässig zugeklappt): Beobachtung, möglicher Kontext,
// Aktivitäts-Signale, Stand und Quellen.
// Eigene Datei, weil die Karte nach der Layout-Regel alle Werte sammelt und das Blatt selbst nur Kopf,
// Kurzkarte und Fusszeile anordnet.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.WhyFactor
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.PriceFormat

/**
 * «Alle Werte und Quellen» auf-/zugeklappt; für die Dauer der Sitzung gemerkt (Prozess), Standard zu.
 */
internal object WhyAllValuesState {
    @Volatile
    var expanded: Boolean = false
}

/**
 * Zugeklappte Karte «Alle Werte und Quellen» (darunter grau «Beobachtung · Mögliche Einordnung ·
 * Quellen», rechts der Pfeil in der Themenfarbe); aufgeklappt darunter [content]. Screenreader:
 * Knopf mit Zustand auf-/zugeklappt.
 */
@Composable
internal fun WhyAllValuesCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    val title = stringResource(R.string.why_all_values)
    val hint = stringResource(R.string.why_all_values_hint)
    val state = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // Auf-/zuklappen: wächst weich, das Blatt bleibt stehen
            .then(if (animate) Modifier.animateContentSize(tween(SWAP_MILLIS)) else Modifier)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .clearAndSetSemantics {
                    contentDescription = "$title, $hint"
                    stateDescription = state
                    role = Role.Button
                    onClick { onToggle(); true }
                    if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
                }
                .padding(horizontal = Spacing.md, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ExpandChevronIcon(expanded = expanded, modifier = Modifier.padding(start = Spacing.sm))
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)) {
                content()
            }
        }
    }
}

/**
 * Inhalt von «Alle Werte und Quellen» — die bisherigen Abschnitte: (1) «Beobachtung» mit
 * Datenstand (Signale, Faktoren, Herkunft je Quelle), (2) «Mögliche Einordnung»
 * («Kurz gesagt», Gleichlauf mit BTC, Details) und (3) der ausführliche Hinweis
 * «kein Ursachenbeweis» mit den Quellen.
 */
@Composable
internal fun WhyAllValues(
    report: WhyReport,
    factors: List<WhyFactor>,
    signals: List<ActivitySignal>,
    base: String,
    animate: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // 1) Beobachtung: gemessene Werte als Tatsache, rechts der Datenstand
        WhySectionTitle(
            stringResource(R.string.why_section_observation),
            trailing = stringResource(R.string.why_data_time, PriceFormat.time(report.dataTime)),
        )
        WhySignals(signals)
        if (factors.isNotEmpty()) {
            WhyFactorCard(factors)
        }
        // Herkunft und Stand je Quelle («Kerzen: Binance · 14:00» …)
        WhyFreshnessLines(report)
        // 2) Mögliche Einordnung — nie als Ursache formuliert
        if (hasWhyContext(report)) {
            WhySectionTitle(
                stringResource(R.string.why_section_context),
                modifier = Modifier.padding(top = Spacing.md)
            )
            WhyContextCard(report, base)
        }
        if (report.reasons.isNotEmpty()) {
            ReasonDetails(report.reasons, animate = animate)
        }
        // 3) Hinweis ausführlich: kein Ursachenbeweis, keine Prognose/Anlageberatung, Quellen
        Column(modifier = Modifier.padding(top = Spacing.sm)) {
            Text(
                stringResource(R.string.why_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.pulse_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // Tatsächliche Quellen des geladenen Berichts, sonst der allgemeine Satz
            Text(
                whySourcesLine(report) ?: stringResource(R.string.why_sources),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Was gerade auffällt (die Signale hinter dem ⚡), höchstens drei; ohne Signale nichts. */
@Composable
internal fun WhySignals(signals: List<ActivitySignal>) {
    if (signals.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        signals.take(3).forEach { signal ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(R.drawable.ic_bolt), null,
                    tint = AppColors.warning,
                    modifier = Modifier.padding(top = 2.dp).size(14.dp)
                )
                Text(
                    ActivityTexts.signal(context, signal),
                    style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * Abschnittsüberschrift im «Was bewegt sich mit?»-Blatt wie überall ([SectionTitle] mit Strich in der
 * Themenfarbe), rechts optional klein der Datenstand («Datenstand 14:32»).
 */
@Composable
internal fun WhySectionTitle(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = SectionTitle.style,
            color = SectionTitle.color,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
                .sectionTitleMarker()
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
    }
}
