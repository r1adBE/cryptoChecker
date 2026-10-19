package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.PlainSummary
import com.cryptochecker.app.domain.market.PulseAlts
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label
import java.text.DateFormat
import java.util.Date

/**
 * «Was gerade auffällt», die eine Heldenkarte oben im Markt-Tab (Entscheid Build 83: nur diese Karte
 * ist betont — grösste Schrift, leichte Tönung; alle Karten darunter ruhig). Liest sich als
 * Was passiert (Schlagzeile + Leitsatz) → «Kurz gesagt» ([plain], bis zu drei Sätze in Alltagssprache)
 * → Belege (Coin-Änderungen, Funding-Chip) → «Was bewegt sich mit?» (aufklappbar: Altcoins vs. Bitcoin
 * und Faktor-Checkliste – / !). Den Stand der Daten zeigt die Status-Pille darüber (nicht doppelt).
 * Kann der Puls nicht laden, steht hier die Fehlerzeile — «Kurz gesagt» bleibt, soweit es Sätze gibt.
 * Screenreader: die Karte ist eine Gruppe; Überzeile, Schlagzeile und Leitsatz ein Element (Überschrift),
 * jeder Satz von «Kurz gesagt» eins, die Belege eins, «Was bewegt sich mit?» separat, jede
 * Checklisten-Zeile ein Element. Farbe trägt nie allein Bedeutung (Vorzeichen, Pfeil und Text).
 */
@Composable
internal fun CryptoPulseCard(state: LoadState<PulseReport>, plain: PlainSummary?, onRetry: () -> Unit) {
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

    // Erste Karte im Tab; den Abstand zu den Karten darunter bringen diese mit
    AppCard(padding = CardPadding.None) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { isTraversalGroup = true }
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
                LoadState.Failed -> {
                    UnavailableRow(onRetry)
                    PlainSummaryBlock(plain)
                }
                is LoadState.Loaded -> PulseContent(
                    report = state.value,
                    plain = plain,
                    expanded = expanded,
                    onToggle = { expanded = !expanded }
                )
            }

            // Ein Hinweis für die ganze Karte: mit «Kurz gesagt» der längere (nennt die 24 Stunden)
            Text(
                stringResource(if (plain != null) R.string.market_plain_footnote else R.string.pulse_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md)
            )
            // Marktbreite und Gesamtmarkt kommen von CoinGecko, ebenso Richtung (Marktkapitalisierung) und
            // «Heute auffällig» (Umsatz-Anteil) in «Kurz gesagt»
            if (state is LoadState.Loaded || plain?.usesCoinGecko == true) CoinGeckoAttribution()
        }
    }
}

/** +1 breit steigend, −1 breit fallend, 0 gemischt oder ruhig. */
internal fun pulseDirection(summary: PulseSummary): Int = when (summary) {
    PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> 1
    PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME -> -1
    PulseSummary.MIXED, PulseSummary.CALM -> 0
}

@Composable
private fun PulseContent(report: PulseReport, plain: PlainSummary?, expanded: Boolean, onToggle: () -> Unit) {
    PulseHeadline(report)
    // «Kurz gesagt» als Körper der Karte direkt unter der Schlagzeile
    PlainSummaryBlock(plain)
    PulseEvidence(report)

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
