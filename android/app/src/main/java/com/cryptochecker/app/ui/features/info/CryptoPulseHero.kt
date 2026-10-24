// Inhalt der Heldenkarte «Was gerade auffällt»: Schlagzeile mit Leitsatz und die Belege darunter
// (Coin-Änderungen, Kennzahl-Chips, Marktbreite, Krypto-Markt). Zwischen beiden stehen in der Karte
// die Sätze von «Kurz gesagt» ([PlainSummaryBlock]).
// Eigene Datei, weil die Karte selbst nur Zustand und Anordnung regelt (wie `CryptoPulseCard.swift`).
package com.cryptochecker.app.ui.features.info

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PulseDetail
import com.cryptochecker.app.domain.market.PulseFunding
import com.cryptochecker.app.domain.market.PulseLeadKind
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.PillTone
import com.cryptochecker.app.ui.components.TonePill
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.displayCompact
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.app.util.PriceFormat

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
        // Fertig formatiert (Ziffern, Abstand und Zeichen der Sprache) statt eines festen «%» in der Übersetzung
        val pct = PercentText.unsigned(lead.volumePercent.toDouble(), 0)
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
 * Schlagzeile (grösste Schrift des Tabs, [displayCompact]: die Heldenkarte ist die einzige betonte
 * Karte) und Leitsatz. Für den Screenreader ein Element und eine Überschrift samt Überzeile:
 * «Was gerade auffällt. Breite Stärke. Bitcoin führt den Markt an.»
 */
@Composable
internal fun PulseHeadline(report: PulseReport) {
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
    Column(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "$overline. $headline. $lead"
            heading()
        }
    ) {
        // Die Überzeile steht sichtbar darüber (mit Info-Zeichen, siehe CryptoPulseCard); gesprochen hier
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pfeil folgt der Richtung, nie dem Farbtausch; gemischt/ruhig ohne Zeichen
            if (direction != 0) {
                Text(
                    if (direction > 0) PriceFormat.ARROW_UP else PriceFormat.ARROW_DOWN,
                    style = MaterialTheme.typography.headline,
                    color = if (direction > 0) PriceColors.up else PriceColors.down,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(
                headline,
                style = MaterialTheme.typography.displayCompact,
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
    }
}

/**
 * Belege unter «Kurz gesagt»: drei Coin-Änderungen, die zutreffenden Kennzahl-Chips, Marktbreite und
 * Krypto-Markt. Für den Screenreader ein Element: «Letzte 24 Stunden: Bitcoin gestiegen um 2.8%, …
 * Hebel-Stimmung optimistisch. … Top 30: 22 steigen, 8 fallen. Krypto-Markt …».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PulseEvidence(report: PulseReport) {
    val context = LocalContext.current
    val coins = listOf(
        Triple("BTC", "Bitcoin", report.btc24h),
        Triple("ETH", "Ethereum", report.eth24h),
        Triple("SOL", "Solana", report.sol24h),
    )
    // Nur zutreffende Chips: Hebel-Stimmung (Funding) optimistisch/pessimistisch. Volumen (Krypto-Markt) und
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
            report.marketCap24h?.let {
                append(", ").append(stringResource(R.string.label_value, periodSpoken, A11yText.change(context, it, decimals = 1)))
            }
        }
    }
    val spoken = buildString {
        val coinList = coins.joinToString(", ") { (_, name, change) -> name + " " + coinSpoken(context, change) }
        append(stringResource(R.string.label_value, periodSpoken, coinList))
        if (chips.isNotEmpty()) append(". ").append(chips.joinToString(", "))
        breadthNote?.let { append(". ").append(it) }
        breadthSpoken?.let { append(". ").append(it) }
        marketSpoken?.let { append(". ").append(it) }
    }

    Column(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
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
