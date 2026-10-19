// Teile der Gesamtkarte im Portfolio: Zeile zu USDT, aufgeklappte «Details» und der kleine Hinweis an einer Teilsumme.
// Eigene Datei, weil `TotalCard` sonst zu lang würde; Zustand (aufgeklappt) bleibt dort, hier wird nur gezeichnet.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.TodayChange
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.PriceFormat

/**
 * Zeile zu USDT auf der Karte selbst ([DepegAlarm.cardNote] mit USDT zum Marktkurs): Kursquelle, «USDT = 1 angenommen»,
 * Einbruch in Warnfarbe oder Kurs über USDC; ohne Hinweis nichts.
 */
@Composable
internal fun UsdtNoteLine(usdtNote: DepegAlarm.CardNote?, priceSourceText: String) {
    when (usdtNote?.kind) {
        // Mit usdtMarket nicht mehr geliefert (Abweichung = Bewertung zum Marktkurs, keine Warnzeile)
        DepegAlarm.CardNoteKind.DEVIATION, null -> Unit
        DepegAlarm.CardNoteKind.PRICE_SOURCE -> Text(
            priceSourceText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        DepegAlarm.CardNoteKind.USDT_ASSUMED -> Text(
            stringResource(R.string.portfolio_usdt_assumed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        // Bestätigter Einbruch: Werte rechnen mit dem Marktkurs — deutlich in Warnfarbe statt «angenommen»
        DepegAlarm.CardNoteKind.USDT_BREAK -> Text(
            stringResource(
                R.string.portfolio_usdt_break,
                com.cryptochecker.app.util.LocaleNumbers.decimal(usdtNote.reading?.price ?: 0.0, 4),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp)
        )
        // Kurs nur aus USDC/USDT: Herkunft nennen und warnen — ein Einbruch von USDC verschiebt sonst still alle Werte
        DepegAlarm.CardNoteKind.VIA_USDC -> Text(
            stringResource(R.string.portfolio_usdt_via_usdc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Aufgeklappte «Details» der Gesamtkarte: 1 J, Belohnungen, Einstand, Realisiert (mit Hinweis zur Steuer-CSV) und
 * Ausgebucht. [onShowHints] klappt die Hinweise unter der Karte auf (Teilsumme angetippt).
 */
@Composable
internal fun TotalCardDetails(
    summary: PortfolioSummary,
    unit: CardUnit,
    costFx: PortfolioCostFxFigures?,
    yearChange: TodayChange?,
    onShowHints: () -> Unit,
) {
    // «↘ −38’412.95 CHF −27.85% · 1J» wie im Portfolio-Widget; ohne Daten keine Zeile
    yearChange?.let {
        TotalChangeLine(
            amountUsd = it.amountUsd,
            percent = it.percent,
            unit = unit,
            period = stringResource(R.string.portfolio_history_range_1y),
            spokenLabel = stringResource(R.string.portfolio_history_period_1y),
            requireAmount = true,
        )
    }
    // Belohnungen im Jahr: Zufluss, nicht Gewinn — klein darunter
    yearChange?.takeIf { it.amountUsd.isFinite() }?.let { change -> rewardsText(change.rewardsUsd, unit)?.let { RewardsNote(it) } }
    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        if (unit.converted) {
            // Einstand in der Anzeigewährung zum Kurs der Erwerbstage, USDT klein darunter (wie der Gesamtwert)
            Column(modifier = Modifier.weight(1f)) {
                val invested = costFx?.invested?.toDouble()
                Metric(
                    label = stringResource(R.string.portfolio_invested),
                    value = invested?.let { maskAmount(PriceFormat.valueWithCurrency(it, unit.code)) } ?: "—",
                )
                if (invested != null && costFx.investedFxMissing) {
                    PartialMarker(
                        text = stringResource(R.string.portfolio_export_incomplete),
                        onClick = onShowHints,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else if (invested != null) {
                    summary.invested?.let {
                        Text(
                            withSmallQuote("≈ " + maskAmount(PortfolioFormat.usdt(it))),
                            style = MaterialTheme.typography.bodySmall.amountNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Metric(
                label = stringResource(R.string.portfolio_invested),
                value = summary.invested?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                modifier = Modifier.weight(1f)
            )
        }
        if (PortfolioFormat.showRealized(summary.realized, summary.realizedIncomplete)) {
            Metric(
                label = stringResource(R.string.portfolio_realized),
                value = maskAmount(PortfolioFormat.realizedText(summary.realized, summary.realizedIncomplete, stringResource(R.string.portfolio_export_incomplete))),
                valueTone = plTone(summary.realized),
                modifier = Modifier.weight(1f)
            )
        }
    }
    // Steuer-CSV rechnet nach den Regeln des Landes (Fristen, Freigrenzen, Pools): kann abweichen
    if (PortfolioFormat.showRealized(summary.realized, summary.realizedIncomplete)) {
        Text(
            stringResource(R.string.tax_export_realized_differs),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    // «Ausgebucht: +X (Wertänderung bis zur Auszahlung)»: steckt im Gewinn/Verlust des Wertverlaufs,
    // nicht in «Seit Kauf»/«realisiert» — nur wenn ≠ 0
    summary.withdrawnGainExact?.toDouble()?.takeIf { it.isFinite() && !PortfolioFormat.isZero(it) }?.let { gain ->
        Text(
            stringResource(
                R.string.portfolio_withdrawn_gain,
                maskAmount(PortfolioFormat.realizedText(gain, summary.withdrawnGainIncomplete, stringResource(R.string.portfolio_export_incomplete))),
            ),
            style = MaterialTheme.typography.bodySmall.amountNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
    Spacer(Modifier.height(Spacing.sm))
}

/**
 * Kleiner Hinweis direkt an einer Teilsumme («ohne 2 Coins», «unvollständig») — nicht nur in den zugeklappten
 * Hinweisen; antippen klappt «Hinweise» auf ([onClick]). Wie iOS `partialMarker`.
 */
@Composable
internal fun PartialMarker(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
