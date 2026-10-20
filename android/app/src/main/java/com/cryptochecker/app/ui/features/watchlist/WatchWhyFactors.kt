@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.BtcLink
import com.cryptochecker.app.domain.activity.WhyFactor
import com.cryptochecker.app.domain.activity.WhyFactorDirection
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySummary
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat

/**
 * «Beobachtung»: die Faktoren als Messwerte (stärkster oben, neutrale abgeblendet, höchstens
 * fünf). Zeilen wie [com.cryptochecker.app.ui.features.info.MarketRow].
 */
@Composable
internal fun WhyFactorCard(factors: List<WhyFactor>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        factors.forEachIndexed { index, factor -> WhyFactorLine(factor, divider = index > 0) }
    }
}

/** Gibt es etwas für «Mögliche Einordnung» («Kurz gesagt» oder Gleichlauf mit BTC)? */
internal fun hasWhyContext(report: WhyReport): Boolean =
    !WhySummary.brief(report.reasons).isEmpty || report.btcLink != null

/** So viele auffällige Faktoren stehen in der Zeile der Karte «Kurz gesagt». */
internal const val WHY_BRIEF_FACTORS = 3

/** Gibt es etwas für die Karte «Kurz gesagt» (Satz oder auffällige Faktoren)? */
internal fun hasWhyBrief(report: WhyReport, factors: List<WhyFactor>): Boolean =
    !WhySummary.brief(report.reasons).isEmpty || factors.any { !it.neutral }

/**
 * Karte oben im «Was bewegt sich mit?»: «Kurz gesagt · Stand 14:00», darunter der eine Satz ([WhySummary])
 * grösser und halbfett, die bis zu drei auffälligsten Faktoren (aus [WhyFactors.rank], ohne
 * neutrale) als eine Zeile schlichter grauer Text («Volumen 2.4× · BTC ↗ +1.8 % · OI +12 %»).
 * Bewusst ohne «Sicherheit»-Stufe: keine Wertung, wie sicher die Einordnung ist.
 * Nur als zeitliches Zusammentreffen formuliert, nie als Ursache. Wie `WatchlistWhyBriefCard` (iOS).
 */
@Composable
internal fun WhyBriefCard(report: WhyReport, factors: List<WhyFactor>) {
    if (!hasWhyBrief(report, factors)) return
    val brief = briefSentence(WhySummary.brief(report.reasons))
    val notable = factors.filter { !it.neutral }.take(WHY_BRIEF_FACTORS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.md)
    ) {
        Text(
            stringResource(R.string.why_brief_head, PriceFormat.time(report.dataTime)),
            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (brief != null) {
            Text(
                brief,
                style = MaterialTheme.typography.title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        if (notable.isNotEmpty()) {
            // Schlichter neutraler Text ohne Pillen, durch « · » getrennt; vorgelesen die ganzen Sätze
            val texts = notable.map { factor -> factor to factorTexts(factor) }
            val line = texts.joinToString(" · ") { (factor, t) -> whyBriefText(factor, t) }
            val spoken = texts.joinToString(", ") { (_, t) -> t.spoken }
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .clearAndSetSemantics { contentDescription = spoken }
            )
        }
    }
}

/**
 * «Mögliche Einordnung»: «Kurz gesagt: …» als ein Satz — nur als zeitliches Zusammentreffen
 * formuliert, nie als sichere Ursache — und der Gleichlauf mit Bitcoin.
 */
@Composable
internal fun WhyContextCard(report: WhyReport, base: String) {
    val brief = briefSentence(WhySummary.brief(report.reasons))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        if (brief != null) {
            val label = stringResource(R.string.why_brief_label)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                    append(" ")
                    append(brief)
                },
                style = MaterialTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // Gleichlauf mit Bitcoin (Stunden-Renditen, Kerzen von «Was bewegt sich mit?»): eng bzw. unabhängig
        report.btcLink?.let { link ->
            Text(
                stringResource(
                    when (link) {
                        BtcLink.TIGHT -> R.string.why_btc_tight
                        BtcLink.INDEPENDENT -> R.string.why_btc_independent
                    },
                    BidiText.isolate(base.trim().uppercase()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (brief != null) Spacing.sm else 0.dp)
            )
        }
    }
}

/**
 * Eine Faktorzeile: Pfeil im Kreis, Titel mit kurzer Nebenzeile, Wert rechts; neutrale
 * abgeblendet. Screenreader: ein Satz («Volumen: höher als üblich, 3,4-mal so viel wie üblich»).
 */
@Composable
private fun WhyFactorLine(factor: WhyFactor, divider: Boolean) {
    val texts = factorTexts(factor)
    Column(modifier = Modifier.fillMaxWidth()) {
        if (divider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm)
                .alpha(if (factor.neutral) NEUTRAL_ALPHA else 1f)
                .clearAndSetSemantics { contentDescription = texts.spoken }
        ) {
            FactorArrow(factor.direction, factor.neutral)
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(
                    texts.title,
                    style = MaterialTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    texts.note,
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (texts.value.isNotEmpty()) {
                Text(
                    texts.value,
                    style = MaterialTheme.typography.title.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Spacing.md)
                )
            }
        }
    }
}

/** Neutrale Faktoren abgeblendet. */
private const val NEUTRAL_ALPHA = 0.6f

/** Kreis mit ↗ / ↘ (schräg wie überall, oder – ohne Richtung); Akzentfarbe, neutral grau — nie Kursfarben. */
@Composable
private fun FactorArrow(direction: WhyFactorDirection, neutral: Boolean) {
    val color = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(FACTOR_ICON).clip(CircleShape).background(color.copy(alpha = 0.12f))
    ) {
        Text(
            when (direction) {
                WhyFactorDirection.UP -> PriceFormat.ARROW_UP
                WhyFactorDirection.DOWN -> PriceFormat.ARROW_DOWN
                WhyFactorDirection.NONE -> "–"
            },
            style = MaterialTheme.typography.title,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private val FACTOR_ICON = 32.dp

/**
 * Platzhalter in der Form des geladenen Inhalts: Karte «Kurz gesagt» (Kopfzeile, zwei Zeilen
 * Satz, Zeile der Faktoren) und darunter die zugeklappte Karte «Alle Werte und Quellen» —
 * gleiche Stile, Abstände und Höhen, beim Eintreffen springt nichts.
 */
@Composable
internal fun WhyBriefSkeleton() {
    // Screenreader: «Lädt …» statt Stille (höflich angesagt, die Form selbst bleibt stumm)
    val loading = stringResource(R.string.loading_hint)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = loading
        liveRegion = LiveRegionMode.Polite
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(Spacing.md)
        ) {
            SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(130.dp))
            SkeletonLine(MaterialTheme.typography.title, Modifier.padding(top = Spacing.xs).fillMaxWidth())
            SkeletonLine(MaterialTheme.typography.title, Modifier.fillMaxWidth(0.6f))
            SkeletonLine(MaterialTheme.typography.bodyMedium, Modifier.padding(top = Spacing.sm).fillMaxWidth(0.75f))
        }
        // Zugeklappte Karte «Alle Werte und Quellen» (Titel, Untertitel)
        Column(
            modifier = Modifier
                .padding(top = Spacing.md)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = Spacing.md, vertical = 12.dp)
        ) {
            SkeletonLine(MaterialTheme.typography.title, Modifier.width(170.dp))
            SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(220.dp))
        }
    }
}

/**
 * Herkunft und Stand unter der Faktorkarte («Beobachtung»), klein und grau: «Kerzen: Binance · 14:00»,
 * «Marktbreite: CoinGecko · 13:05», «Futures: Bybit · Open Interest vor 2 Std. verglichen»,
 * «Fear & Greed: heute». Ausweichquellen und Richtwerte sind benannt.
 */
@Composable
internal fun WhyFreshnessLines(report: WhyReport) {
    val lines = whyFreshness(report)
    if (lines.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
