@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.BtcLink
import com.cryptochecker.app.domain.activity.FearGreedLevel
import com.cryptochecker.app.domain.activity.WhyBrief
import com.cryptochecker.app.domain.activity.WhyConfidence
import com.cryptochecker.app.domain.activity.WhyConfidenceLevel
import com.cryptochecker.app.domain.activity.WhyConfidenceRules
import com.cryptochecker.app.domain.activity.WhyExtra
import com.cryptochecker.app.domain.activity.WhyFactor
import com.cryptochecker.app.domain.activity.WhyFactorDirection
import com.cryptochecker.app.domain.activity.WhyFactorKind
import com.cryptochecker.app.domain.activity.WhyFactorNote
import com.cryptochecker.app.domain.activity.WhyFactors
import com.cryptochecker.app.domain.activity.WhyHeadline
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySummary
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import kotlin.math.roundToInt

/**
 * «Wahrscheinliche Gründe · Sicherheit: mittel», darunter die Faktoren (stärkster oben,
 * neutrale abgeblendet, höchstens fünf) und «Kurz gesagt: …» als ein Satz — nie als
 * sichere Ursache formuliert. Zeilen wie [com.cryptochecker.app.ui.features.info.MarketRow].
 */
@Composable
internal fun WhyFactorCard(report: WhyReport, factors: List<WhyFactor>, base: String) {
    val confidence = WhyConfidenceRules.evaluate(report.reasons, report.hasMarketData)
    val brief = briefSentence(WhySummary.brief(report.reasons))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.why_summary_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                confidence?.let {
                    Text(
                        stringResource(R.string.why_confidence, confidenceLabel(it.level)),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = Spacing.sm)
                    )
                }
            }
            confidence?.let {
                Text(
                    confidenceExplanation(it),
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        factors.forEach { WhyFactorLine(it) }
        if (brief != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val label = stringResource(R.string.why_brief_label)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                    append(" ")
                    append(brief)
                },
                style = MaterialTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.md)
            )
        }
        // Gleichlauf mit Bitcoin (Stunden-Renditen, Kerzen von «Warum?»): eng bzw. unabhängig
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
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}

/** «Kurz gesagt»: genau ein Satz — die Einordnung, sonst der erste Zusatz; null = keiner. */
@Composable
private fun briefSentence(brief: WhyBrief): String? {
    brief.headline?.let { headline ->
        return stringResource(
            when (headline) {
                WhyHeadline.CALM -> R.string.why_summary_calm
                WhyHeadline.COIN_VOLUME -> R.string.why_summary_coin_volume
                WhyHeadline.COIN -> R.string.why_summary_coin
                WhyHeadline.AGAINST -> R.string.why_summary_against
                WhyHeadline.MARKET_VOLUME -> R.string.why_summary_market_volume
                WhyHeadline.MARKET -> R.string.why_summary_market
            }
        )
    }
    return brief.extras.firstOrNull()?.let { extra ->
        stringResource(
            when (extra) {
                WhyExtra.THIN -> R.string.why_summary_thin
                WhyExtra.LONGS -> R.string.why_summary_longs
                WhyExtra.SHORTS -> R.string.why_summary_shorts
            }
        )
    }
}

/** Texte einer Faktorzeile: Titel, Wert rechts, Nebenzeile und der gesprochene Satz. */
private data class FactorTexts(val title: String, val value: String, val note: String, val spoken: String)

/**
 * Eine Faktorzeile: Pfeil im Kreis, Titel mit kurzer Nebenzeile, Wert rechts; neutrale
 * abgeblendet. Screenreader: ein Satz («Volumen: höher als üblich, 3,4-mal so viel wie üblich»).
 */
@Composable
private fun WhyFactorLine(factor: WhyFactor) {
    val texts = factorTexts(factor)
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

/** Kreis mit ↑ / ↓ (oder – ohne Richtung); Akzentfarbe, neutral grau — nie Kursfarben. */
@Composable
private fun FactorArrow(direction: WhyFactorDirection, neutral: Boolean) {
    val color = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(FACTOR_ICON).clip(CircleShape).background(color.copy(alpha = 0.12f))
    ) {
        Text(
            when (direction) {
                WhyFactorDirection.UP -> "↑"
                WhyFactorDirection.DOWN -> "↓"
                WhyFactorDirection.NONE -> "–"
            },
            style = MaterialTheme.typography.title,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private val FACTOR_ICON = 32.dp

@Composable
private fun factorTexts(f: WhyFactor): FactorTexts {
    val context = LocalContext.current
    fun pct(v: Double, decimals: Int = 1) = ActivityTexts.percent(v, decimals)
    val title: String
    var value = ""
    var spokenValue = ""
    val note: String
    when (f.kind) {
        WhyFactorKind.VOLUME -> {
            val number = ActivityTexts.factor(f.value)
            title = stringResource(R.string.factor_volume)
            value = "$number×"
            spokenValue = stringResource(R.string.factor_spoken_volume, number)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.VOLUME_HIGHER -> R.string.why_fn_volume_higher
                    WhyFactorNote.VOLUME_LOWER -> R.string.why_fn_volume_lower
                    else -> R.string.why_fn_volume_usual
                }
            )
        }
        WhyFactorKind.MARKET -> {
            title = stringResource(R.string.why_factor_market)
            // Bei Bitcoin selbst steht BTC oben: rechts ETH zum Vergleich
            val leader = f.note == WhyFactorNote.MARKET_LEADER_MOVES || f.note == WhyFactorNote.MARKET_LEADER_CALM
            val (symbol, change) = if (leader) "ETH" to f.secondary else "BTC" to f.value
            if (change != null) {
                value = "$symbol " + pct(change)
                spokenValue = "$symbol, " + A11yText.change(context, change, decimals = 1)
            }
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.MARKET_PULLS -> R.string.why_fn_market_pulls
                    WhyFactorNote.MARKET_COIN_LAGS -> R.string.why_fn_market_lags
                    WhyFactorNote.MARKET_COIN_ALONE -> R.string.why_fn_market_alone
                    WhyFactorNote.MARKET_AGAINST -> R.string.why_fn_market_against
                    WhyFactorNote.MARKET_LEADER_MOVES -> R.string.why_fn_leader_moves
                    WhyFactorNote.MARKET_LEADER_CALM -> R.string.why_fn_leader_calm
                    else -> R.string.why_fn_market_calm
                }
            )
        }
        WhyFactorKind.VOLATILITY -> {
            val number = ActivityTexts.factor(f.value)
            title = stringResource(R.string.factor_volatility)
            value = "$number×"
            spokenValue = stringResource(R.string.factor_spoken_volatility, number)
            note = stringResource(
                if (f.note == WhyFactorNote.VOLATILITY_STRONGER) R.string.why_fn_volatility_stronger
                else R.string.why_fn_volatility_usual
            )
        }
        WhyFactorKind.OPEN_INTEREST -> {
            title = stringResource(R.string.why_factor_futures)
            value = pct(f.value)
            spokenValue = A11yText.change(context, f.value, decimals = 1)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.OI_UP -> R.string.why_fn_oi_up
                    WhyFactorNote.OI_DOWN -> R.string.why_fn_oi_down
                    else -> R.string.why_fn_oi_flat
                }
            )
        }
        WhyFactorKind.NEAR_HIGH -> {
            title = stringResource(R.string.why_factor_near_high)
            if (f.note == WhyFactorNote.HIGH_BELOW) {
                value = pct(-f.value)
                spokenValue = A11yText.change(context, -f.value, decimals = 1)
            }
            note = stringResource(
                if (f.note == WhyFactorNote.HIGH_AT) R.string.why_fn_high_at else R.string.why_fn_high_below
            )
        }
        WhyFactorKind.FUNDING -> {
            title = stringResource(R.string.why_factor_funding)
            value = fundingText(f.value)
            spokenValue = value
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.FUNDING_LONGS -> R.string.why_fn_funding_longs
                    WhyFactorNote.FUNDING_SHORTS -> R.string.why_fn_funding_shorts
                    else -> R.string.why_fn_funding_neutral
                }
            )
        }
        WhyFactorKind.SENTIMENT -> {
            val points = f.value.roundToInt()
            title = stringResource(R.string.factor_fear_greed)
            value = LocaleNumbers.integer(points)
            spokenValue = value
            note = fearGreedLabel(points)
        }
    }
    val spoken = "$title: $note" + (if (spokenValue.isNotBlank()) ", $spokenValue" else "") + "."
    return FactorTexts(title, value, note, spoken)
}

@Composable
private fun confidenceLabel(level: WhyConfidenceLevel): String = stringResource(
    when (level) {
        WhyConfidenceLevel.HIGH -> R.string.why_confidence_high
        WhyConfidenceLevel.MEDIUM -> R.string.why_confidence_medium
        WhyConfidenceLevel.LOW -> R.string.why_confidence_low
    }
)

/** «2 von 4 Hinweisen deuten darauf hin», bei Lücken mit «· Daten unvollständig». */
@Composable
private fun confidenceExplanation(c: WhyConfidence): String {
    val hints = pluralStringResource(R.plurals.why_confidence_hints, c.agreeing, c.agreeing, c.total)
    return if (c.partialData) hints + " · " + stringResource(R.string.why_confidence_partial) else hints
}

/**
 * Platzhalter in der Form von [WhyFactorCard] und dem Knopf «Details anzeigen»: Kopf,
 * Zeile zur Sicherheit, fünf Faktorzeilen (Kreis, Titel, Nebenzeile, Wert) und zwei Zeilen
 * «Kurz gesagt» — gleiche Stile, Abstände und Höhen, beim Eintreffen springt nichts.
 */
@Composable
internal fun FactorCardSkeleton() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonLine(MaterialTheme.typography.labelLarge, Modifier.width(140.dp))
                Spacer(Modifier.weight(1f))
                SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(96.dp))
            }
            SkeletonLine(
                MaterialTheme.typography.labelSmall,
                Modifier.padding(top = Spacing.xs).width(170.dp)
            )
            Spacer(Modifier.height(Spacing.sm))
            repeat(WhyFactors.MAX_FACTORS) { index ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)
                ) {
                    Box(Modifier.size(FACTOR_ICON).clip(CircleShape).background(block))
                    Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                        SkeletonLine(MaterialTheme.typography.body, Modifier.width(90.dp + 12.dp * (index % 3)))
                        SkeletonLine(MaterialTheme.typography.label, Modifier.width(130.dp - 10.dp * (index % 2)))
                    }
                    SkeletonLine(
                        MaterialTheme.typography.title,
                        Modifier.padding(start = Spacing.md).width(52.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SkeletonLine(MaterialTheme.typography.body, Modifier.padding(top = Spacing.md).fillMaxWidth())
            SkeletonLine(MaterialTheme.typography.body, Modifier.fillMaxWidth(0.6f))
        }
        // Wie der Knopf «Details anzeigen» (TextButton, 40 dp) mit dem Abstand davor
        Box(
            Modifier.padding(top = Spacing.sm).height(40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SkeletonLine(MaterialTheme.typography.labelLarge, Modifier.padding(start = 12.dp).width(110.dp))
        }
    }
}

/** Funding mit drei Nachkommastellen, z. B. «+0.080 %». */
internal fun fundingText(value: Double): String = ActivityTexts.percent(value, 3)

@Composable
internal fun withOpenInterest(text: String, change: Double?): String =
    if (change == null) text
    else text + " " + stringResource(R.string.why_open_interest_change, ActivityTexts.percent(change, 1))

internal fun signedInt(value: Int): String = when {
    value > 0 -> "+" + LocaleNumbers.integer(value)
    value < 0 -> "−" + LocaleNumbers.integer(-value)
    else -> "±" + LocaleNumbers.integer(0)
}

@Composable
internal fun fearGreedLabel(value: Int): String = stringResource(
    when (ActivityAnalyzer.fearGreedLevel(value)) {
        FearGreedLevel.EXTREME_FEAR -> R.string.fng_extreme_fear
        FearGreedLevel.FEAR -> R.string.fng_fear
        FearGreedLevel.NEUTRAL -> R.string.fng_neutral
        FearGreedLevel.GREED -> R.string.fng_greed
        FearGreedLevel.EXTREME_GREED -> R.string.fng_extreme_greed
    }
)
