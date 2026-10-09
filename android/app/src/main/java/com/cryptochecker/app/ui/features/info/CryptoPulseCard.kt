package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PulseAlts
import com.cryptochecker.app.domain.market.PulseBreadthNote
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseFactor
import com.cryptochecker.app.domain.market.PulseFactorKind
import com.cryptochecker.app.domain.market.PulseFunding
import com.cryptochecker.app.domain.market.PulseLeadKind
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.FactorRow
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPill
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * «Was gerade auffällt», Held des Markt-Tabs. Liest sich als
 * Was passiert (Schlagzeile + Leitsatz) → Belege (Coin-Pillen, Funding-Chip)
 * → Warum (aufklappbar: Altcoins vs. Bitcoin und Faktor-Checkliste – / !).
 * Screenreader: Überzeile, Schlagzeile, Leitsatz und Chips als ein Element,
 * «Warum?» separat, jede Checklisten-Zeile ein Element.
 * Farbe trägt nie allein Bedeutung (Vorzeichen, Pfeil und Text).
 */
@Composable
internal fun CryptoPulseCard(state: LoadState<PulseReport>, onRetry: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val animate = !rememberReduceMotion()

    // Sehr leichte Tönung oben in der Kursfarbe der Richtung (≤ 8 %), nicht bei
    // hohem Kontrast und nicht bei gemischt/ruhig
    val direction = if (state is LoadState.Loaded) pulseDirection(state.value.summary) else 0
    val tint = when {
        LocalHighContrast.current || direction == 0 -> null
        direction > 0 -> PriceColors.up
        else -> PriceColors.down
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // Erste Karte im Tab; den Abstand zu Fear & Greed darunter bringt deren Karte mit
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (tint != null) Modifier.background(
                        Brush.verticalGradient(listOf(tint.copy(alpha = 0.08f), Color.Transparent))
                    ) else Modifier
                )
                .sizeAnimation(animate)
                .padding(Spacing.lg)
        ) {
            // Geladen: Überzeile gehört zum Screenreader-Element der Schlagzeile
            if (state !is LoadState.Loaded) {
                PulseOverline(Modifier.padding(bottom = 8.dp).semantics { heading() })
            }

            when (state) {
                LoadState.Loading -> PulseSkeleton()
                LoadState.Failed -> UnavailableRow(onRetry)
                is LoadState.Loaded -> PulseContent(
                    report = state.value,
                    expanded = expanded,
                    onToggle = { expanded = !expanded }
                )
            }

            Text(
                stringResource(R.string.pulse_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Platzhalter in der Form von [PulseContent] (Überzeile steht schon darüber):
 * Schlagzeile, Leitsatz über zwei Zeilen, drei Coin-Pillen in Chip-Höhe, Zeile mit
 * Stand und «Warum?». Höhen aus der echten Typografie, damit beim Laden nichts springt.
 */
@Composable
private fun PulseSkeleton() {
    SkeletonPulse(modifier = Modifier.fillMaxWidth()) {
        SkeletonLine(MaterialTheme.typography.headline, Modifier.fillMaxWidth(0.55f))
        SkeletonLine(MaterialTheme.typography.bodyLarge, Modifier.padding(top = Spacing.xs).fillMaxWidth())
        SkeletonLine(MaterialTheme.typography.bodyLarge, Modifier.fillMaxWidth(0.7f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            repeat(3) {
                // Gleiche Form und Höhe wie [PulseCoinChip], nur ohne Inhalt
                SkeletonPill(
                    MaterialTheme.typography.labelMedium.amountNumbers(),
                    Modifier.weight(1f),
                    horizontal = 8.dp,
                    vertical = 4.dp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            SkeletonLine(MaterialTheme.typography.labelSmall, Modifier.width(96.dp))
            // Platz des «Warum?»-Knopfs (Textknopf samt Mindest-Tippfläche 48 dp)
            Spacer(modifier = Modifier.weight(1f).height(48.dp))
        }
    }
}

/** +1 breit steigend, −1 breit fallend, 0 gemischt oder ruhig. */
private fun pulseDirection(summary: PulseSummary): Int = when (summary) {
    PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> 1
    PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME -> -1
    PulseSummary.MIXED, PulseSummary.CALM -> 0
}

@Composable
private fun PulseContent(report: PulseReport, expanded: Boolean, onToggle: () -> Unit) {
    PulseHero(report)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            stringResource(
                R.string.pulse_updated,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.time))
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggle) {
            Text(stringResource(if (expanded) R.string.pulse_less else R.string.pulse_why_action))
        }
    }

    if (expanded) {
        // BTC/ETH/SOL stehen schon in den Coin-Pillen oben — hier nur Altcoins vs. Bitcoin
        PulseSectionTitle(stringResource(R.string.pulse_section_market))
        PulseLine(
            stringResource(
                when (report.alts) {
                    PulseAlts.STRONGER -> R.string.pulse_alts_stronger
                    PulseAlts.WEAKER -> R.string.pulse_alts_weaker
                    PulseAlts.EVEN -> R.string.pulse_alts_even
                }
            )
        )

        // Was «Top 30» bedeutet (nur mit Marktbreite)
        report.breadth?.let { PulseLine(stringResource(R.string.pulse_breadth_hint, it.total)) }

        val factors = CryptoPulse.factors(report)
        if (factors.isNotEmpty()) {
            PulseSectionTitle(stringResource(R.string.pulse_section_factors))
            factors.forEach { PulseFactorRow(report, it) }
        }
    }
}

/**
 * Eine Zeile der Checkliste, z. B. «! Funding … +0.045%». Volumen, Fear & Greed
 * und Gas stehen in eigenen Karten des Tabs (siehe [CryptoPulse.factors]).
 */
@Composable
private fun PulseFactorRow(report: PulseReport, factor: PulseFactor) {
    when (factor.kind) {
        PulseFactorKind.FUNDING -> report.fundingPercent?.let { percent ->
            FactorRow(
                mark = factor.mark,
                title = stringResource(R.string.factor_funding),
                value = ActivityTexts.percent(percent, 3)
            )
        }
    }
}

/** Kleine Überzeile «Was gerade auffällt». */
@Composable
private fun PulseOverline(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.pulse_now_title),
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = modifier.sectionTitleMarker()
    )
}

/** Leitsatz aus [CryptoPulse.leadSentence]: ein oder zwei ganze Sätze. */
@Composable
private fun pulseLeadText(report: PulseReport): String {
    val lead = CryptoPulse.leadSentence(report)
    val first = stringResource(
        when (lead.kind) {
            PulseLeadKind.BTC_LEADS -> R.string.pulse_lead_btc_leads
            PulseLeadKind.ALTS_STRONGER -> R.string.pulse_lead_alts_stronger
            PulseLeadKind.BTC_STRONGER -> R.string.pulse_lead_btc_stronger
            PulseLeadKind.BROAD_UP -> R.string.pulse_lead_broad_up
            PulseLeadKind.BROAD_DOWN -> R.string.pulse_lead_broad_down
            PulseLeadKind.DRIFT_UP -> R.string.pulse_lead_drift_up
            PulseLeadKind.DRIFT_DOWN -> R.string.pulse_lead_drift_down
            PulseLeadKind.MIXED -> R.string.pulse_lead_mixed
            PulseLeadKind.CALM -> R.string.pulse_lead_calm
        }
    )
    val second = lead.detail?.let { detail ->
        val pct = lead.volumePercent
        when (detail) {
            PulseDetail.VOL_ABOVE -> stringResource(R.string.pulse_detail_vol_above, pct)
            PulseDetail.VOL_ABOVE_FUND_NEUTRAL -> stringResource(R.string.pulse_detail_vol_above_fund_neutral, pct)
            PulseDetail.VOL_ABOVE_FUND_HIGH -> stringResource(R.string.pulse_detail_vol_above_fund_high, pct)
            PulseDetail.VOL_ABOVE_FUND_NEGATIVE -> stringResource(R.string.pulse_detail_vol_above_fund_negative, pct)
            PulseDetail.VOL_BELOW -> stringResource(R.string.pulse_detail_vol_below, pct)
            PulseDetail.VOL_BELOW_FUND_NEUTRAL -> stringResource(R.string.pulse_detail_vol_below_fund_neutral, pct)
            PulseDetail.VOL_BELOW_FUND_HIGH -> stringResource(R.string.pulse_detail_vol_below_fund_high, pct)
            PulseDetail.VOL_BELOW_FUND_NEGATIVE -> stringResource(R.string.pulse_detail_vol_below_fund_negative, pct)
            PulseDetail.VOL_NORMAL -> stringResource(R.string.pulse_detail_vol_normal)
            PulseDetail.VOL_NORMAL_FUND_NEUTRAL -> stringResource(R.string.pulse_detail_vol_normal_fund_neutral)
            PulseDetail.VOL_NORMAL_FUND_HIGH -> stringResource(R.string.pulse_detail_vol_normal_fund_high)
            PulseDetail.VOL_NORMAL_FUND_NEGATIVE -> stringResource(R.string.pulse_detail_vol_normal_fund_negative)
            PulseDetail.FUND_NEUTRAL -> stringResource(R.string.pulse_detail_fund_neutral)
            PulseDetail.FUND_HIGH -> stringResource(R.string.pulse_detail_fund_high)
            PulseDetail.FUND_NEGATIVE -> stringResource(R.string.pulse_detail_fund_negative)
        }
    }
    return if (second == null) first else "$first $second"
}

/**
 * Überzeile, Schlagzeile, Leitsatz, drei Coin-Pillen und die zutreffenden
 * Kennzahl-Chips. Für den Screenreader ein Element: «Was gerade auffällt.
 * Breite Stärke. Bitcoin führt den Markt an. … Bitcoin gestiegen um 2.8%, … Funding erhöht».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PulseHero(report: PulseReport) {
    val context = LocalContext.current
    val direction = pulseDirection(report.summary)
    val headline = stringResource(
        when (report.summary) {
            PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> R.string.pulse_headline_up
            PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME -> R.string.pulse_headline_down
            PulseSummary.MIXED -> R.string.pulse_headline_mixed
            PulseSummary.CALM -> R.string.pulse_headline_calm
        }
    )
    val overline = stringResource(R.string.pulse_now_title)
    val lead = pulseLeadText(report)
    val coins = listOf(
        Triple("BTC", "Bitcoin", report.btc24h),
        Triple("ETH", "Ethereum", report.eth24h),
        Triple("SOL", "Solana", report.sol24h),
    )
    // Nur zutreffende Chips: Funding erhöht/negativ. Volumen (Krypto-Markt) und
    // Fear & Greed haben eigene Karten im Tab und erscheinen hier nicht doppelt.
    val chips = buildList {
        when (report.funding) {
            PulseFunding.HIGH -> add(stringResource(R.string.pulse_chip_funding_high))
            PulseFunding.NEGATIVE -> add(stringResource(R.string.pulse_chip_funding_negative))
            PulseFunding.SLIGHT, PulseFunding.NEUTRAL, null -> Unit
        }
    }
    // Marktbreite und Krypto-Markt gesamt (nur mit Daten)
    val breadth = report.breadth
    val breadthNote = report.breadthNote?.let { stringResource(breadthNoteRes(it)) }
    val breadthLabel = breadth?.let { stringResource(R.string.pulse_breadth_label, it.total) }
    val breadthSpoken = breadth?.let { stringResource(R.string.pulse_breadth_spoken, it.total, it.up, it.down) }
    val marketTitle = stringResource(R.string.market_cap_title)
    val locale = LocalConfiguration.current.locales[0]
    val marketCap = report.marketCapUsd?.let { remember(it, locale) { CompactAmount.format(it, "USD", locale) } }
    val marketSpoken = marketCap?.let { cap ->
        buildString {
            append(marketTitle).append(' ').append(cap)
            report.marketCap24h?.let { append(", ").append(A11yText.change(context, it, decimals = 1)) }
        }
    }
    val spoken = buildString {
        append(overline).append(". ")
        append(headline).append(". ")
        append(lead).append(" ")
        append(coins.joinToString(", ") { (_, name, change) -> name + " " + coinSpoken(context, change) })
        if (chips.isNotEmpty()) append(". ").append(chips.joinToString(", "))
        breadthNote?.let { append(". ").append(it) }
        breadthSpoken?.let { append(". ").append(it) }
        marketSpoken?.let { append(". ").append(it) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = spoken
            heading()
        }
    ) {
        PulseOverline(Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pfeil folgt der Richtung, nie dem Farbtausch; gemischt/ruhig ohne Zeichen
            if (direction != 0) {
                Text(
                    if (direction > 0) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (direction > 0) PriceColors.up else PriceColors.down,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(
                headline,
                style = MaterialTheme.typography.headline,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            lead,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.xs)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 12.dp)
        ) {
            coins.forEach { (symbol, _, change) ->
                PulseCoinChip(symbol, change, Modifier.weight(1f).fillMaxHeight())
            }
        }

        if (chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            ) {
                chips.forEach { PulseMetricChip(it) }
            }
        }

        // «Top 30   ▲ 22 · ▼ 8» und «Krypto-Markt   3.42 Bio. $  ▲ +2.1%»
        if (breadth != null || marketCap != null) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                if (breadth != null && breadthLabel != null) {
                    PulseFactRow(breadthLabel) {
                        Text(
                            "▲ " + LocaleNumbers.integer(breadth.up, locale),
                            style = MaterialTheme.typography.labelLarge.amountNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = PriceColors.up
                        )
                        Text(
                            "  ·  ",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "▼ " + LocaleNumbers.integer(breadth.down, locale),
                            style = MaterialTheme.typography.labelLarge.amountNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = PriceColors.down
                        )
                    }
                }
                if (marketCap != null) {
                    PulseFactRow(marketTitle) {
                        Text(
                            marketCap,
                            style = MaterialTheme.typography.labelLarge.amountNumbers(),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        report.marketCap24h?.let { change ->
                            ChangePill(change, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
        if (breadthNote != null) {
            Text(
                breadthNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}

/** Zeile «Bezeichnung … Wert» unter den Coin-Pillen. */
@Composable
private fun PulseFactRow(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        value()
    }
}

private fun breadthNoteRes(note: PulseBreadthNote): Int = when (note) {
    PulseBreadthNote.BROAD_UP -> R.string.pulse_breadth_note_broad_up
    PulseBreadthNote.BROAD_DOWN -> R.string.pulse_breadth_note_broad_down
    PulseBreadthNote.NARROW_UP -> R.string.pulse_breadth_note_narrow_up
    PulseBreadthNote.NARROW_DOWN -> R.string.pulse_breadth_note_narrow_down
}

/** «BTC ▲ +2.8%» im Stil der Prozent-Pille der Merkliste: Kursfarbe auf 14 % Tönung. */
@Composable
private fun PulseCoinChip(symbol: String, change: Double, modifier: Modifier = Modifier) {
    val flat = abs(change) < 0.05
    val color = if (flat) MaterialTheme.colorScheme.onSurfaceVariant else PriceColors.forChange(change)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            symbol + " " + coinChangeText(change),
            style = MaterialTheme.typography.labelMedium.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = color,
            textAlign = TextAlign.Center
        )
    }
}

/** Neutraler Chip (grauer Rand, keine Bedeutungsfarbe). */
@Composable
private fun PulseMetricChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = Spacing.sm, vertical = 4.dp)
    )
}

/** «▲ +3.2%», «▼ −1.4%» oder «0.0%» bei praktisch unverändert (unter 0.05 %). */
private fun coinChangeText(change: Double): String {
    if (abs(change) < 0.05) return "%.1f".format(0.0) + "%"
    val sign = if (change > 0) "+" else "−"
    return PriceFormat.changeArrow(change) + " " + sign + "%.1f".format(abs(change)) + "%"
}

/** «gestiegen um 2.8%» mit einer Nachkommastelle wie angezeigt; flach = «unverändert». */
private fun coinSpoken(context: Context, change: Double): String =
    A11yText.change(context, if (abs(change) < 0.05) 0.0 else change, decimals = 1)

@Composable
private fun PulseSectionTitle(text: String) {
    Text(
        text,
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }.sectionTitleMarker()
    )
}

@Composable
private fun PulseLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
}
