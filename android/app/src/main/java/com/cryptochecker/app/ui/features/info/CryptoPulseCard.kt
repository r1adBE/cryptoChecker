package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PulseAlts
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseFunding
import com.cryptochecker.app.domain.market.PulseLeadKind
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.PillTone
import com.cryptochecker.app.ui.components.TonePill
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.app.util.PriceFormat
import java.text.DateFormat
import java.util.Date

/**
 * «Was gerade auffällt», Held des Markt-Tabs. Liest sich als
 * Was passiert (Schlagzeile + Leitsatz) → Belege (Coin-Änderungen, Funding-Chip)
 * → «Was bewegt sich mit?» (aufklappbar: Altcoins vs. Bitcoin und Faktor-Checkliste – / !).
 * Screenreader: Überzeile, Schlagzeile, Leitsatz und Chips als ein Element,
 * «Was bewegt sich mit?» separat, jede Checklisten-Zeile ein Element.
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

    // Erste Karte im Tab; den Abstand zu Fear & Greed darunter bringt deren Karte mit
    AppCard(padding = CardPadding.None) {
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
            // Überzeile mit Info-Zeichen; geladen gehört die Überzeile zum Screenreader-Element der
            // Schlagzeile (hier dann still), das Info-Zeichen bleibt ein eigener Knopf
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                PulseOverline(
                    Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (state is LoadState.Loaded) Modifier.clearAndSetSemantics { }
                            else Modifier.semantics { heading() }
                        )
                )
                MarketInfoButton(MarketInfoTopic.PULSE, Modifier.padding(start = 2.dp))
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
            // Marktbreite und Gesamtmarkt kommen von CoinGecko
            if (state is LoadState.Loaded) CoinGeckoAttribution()
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
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        // Klappt nur auf (kein Seitenwechsel): Pfeil nach unten bzw. oben wie bei «Einordnung»
        ExpandToggleButton(
            text = stringResource(if (expanded) R.string.pulse_less else R.string.pulse_details_action),
            expanded = expanded,
            onClick = onToggle,
        )
    }

    if (expanded) {
        // BTC/ETH/SOL stehen schon in der Coin-Zeile oben — hier nur Altcoins vs. Bitcoin
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
 * Schlagzeile, Leitsatz, drei Coin-Änderungen und die zutreffenden Kennzahl-Chips (die Überzeile
 * steht darüber). Für den Screenreader ein Element samt Überzeile: «Was gerade auffällt.
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
    // Zeitraum aller Veränderungen der Karte: 24 Stunden (Binance-24-h-Ticker bzw. Stundenkerzen,
    // Top 30 und Krypto-Markt von CoinGecko, ebenfalls 24 h) — sichtbar als «24h»
    val period = stringResource(R.string.why_change_24h)
    val periodSpoken = stringResource(R.string.pulse_period_24h_spoken)
    val breadthLabel = breadth?.let { stringResource(R.string.pulse_breadth_label, it.total) + " · " + period }
    val breadthSpoken = breadth?.let { stringResource(R.string.pulse_breadth_spoken, it.total, it.up, it.down) }
    val marketTitle = stringResource(R.string.market_cap_title)
    val locale = LocalConfiguration.current.locales[0]
    val marketCap = report.marketCapUsd?.let { remember(it, locale) { CompactAmount.format(it, "USD", locale) } }
    val marketSpoken = marketCap?.let { cap ->
        buildString {
            append(marketTitle).append(' ').append(cap)
            report.marketCap24h?.let { append(", ").append(periodSpoken).append(": ").append(A11yText.change(context, it, decimals = 1)) }
        }
    }
    val spoken = buildString {
        append(overline).append(". ")
        append(headline).append(". ")
        append(lead).append(" ")
        append(periodSpoken).append(": ")
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
        // Die Überzeile steht sichtbar darüber (mit Info-Zeichen, siehe CryptoPulseCard); gesprochen hier
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pfeil folgt der Richtung, nie dem Farbtausch; gemischt/ruhig ohne Zeichen
            if (direction != 0) {
                Text(
                    if (direction > 0) PriceFormat.ARROW_UP else PriceFormat.ARROW_DOWN,
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

        // «24h  BTC ↘ −0.3%  ETH ↗ +1.2%»: Zeitraum klein und grau vorne, Coins als farbige Schrift
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(
                period,
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            coins.forEach { (symbol, _, change) -> PulseCoinChange(symbol, change) }
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

        // «Top 30 · 24h   ↗ 22 steigen  ↘ 8 fallen» und «Krypto-Markt   3.42 Bio. $  ↗ +2.1% · 24h»
        if (breadth != null || marketCap != null) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                if (breadth != null && breadthLabel != null) {
                    PulseFactRow(breadthLabel) {
                        // Wie die Puls-Zeile der Merkliste: Zähler als farbige Schrift mit schrägem Pfeil
                        TonePill(
                            text = PriceFormat.ARROW_UP + " " +
                                pluralStringResource(R.plurals.watchlist_pulse_up, breadth.up, breadth.up),
                            tone = PillTone.UP,
                        )
                        TonePill(
                            text = PriceFormat.ARROW_DOWN + " " +
                                pluralStringResource(R.plurals.watchlist_pulse_down, breadth.down, breadth.down),
                            tone = PillTone.DOWN,
                            modifier = Modifier.padding(start = Spacing.sm)
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
                            // «2.82 Bio. USD  ↘ −2.77% · 24h»: Änderung farbig, Zeitraum klein und grau
                            // dahinter (die Summe selbst ist ein Stand, keine Veränderung)
                            ChangePill(change, modifier = Modifier.padding(start = 8.dp))
                            Text(
                                "· $period",
                                style = MaterialTheme.typography.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp)
                            )
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
