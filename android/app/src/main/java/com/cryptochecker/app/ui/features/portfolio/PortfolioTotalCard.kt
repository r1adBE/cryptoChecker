@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.components.changePill
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.display
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Gesamtwert (gross, rollende Ziffern), «heute», ± unrealisiert, investiert/realisiert,
 * Umrechnung und Stand der Kurse. Kopf wie auf iOS: sehr dezenter Verlauf in der
 * Akzentfarbe (16 % → 4 %), Ecken 20 dp, Rand in der Akzentfarbe (25 %).
 */
@Composable
internal fun TotalCard(
    summary: PortfolioSummary,
    updatedAt: Long,
    currency: String,
    fxRate: Double?,
    todayPercent: Double?,
) {
    val accent = MaterialTheme.colorScheme.primary
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Von oben links nach unten rechts, wie LinearGradient(.topLeading → .bottomTrailing)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.04f))))
                .padding(Spacing.lg)
        ) {
            Text(
                stringResource(R.string.portfolio_total_value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FittedTotal(
                text = maskAmount(PortfolioFormat.usdt(summary.totalValue)),
                value = summary.totalValue,
                modifier = Modifier.padding(top = 2.dp)
            )
            // «≈ 12’345.67 CHF» — nur mit Devisenkurs und nicht bei USD
            if (currency != "USD" && fxRate != null) {
                Text(
                    "≈ " + maskAmount(PriceFormat.valueWithCurrency(summary.totalValue * fxRate, currency)),
                    style = MaterialTheme.typography.bodyMedium.amountNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Veränderung heute (wie im Portfolio-Widget), sobald es eine Vergleichsbasis gibt
            if (todayPercent != null) {
                TodayPill(todayPercent, modifier = Modifier.padding(top = Spacing.xs))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.sm)) {
                Text(
                    summary.unrealized?.let { maskAmount(PortfolioFormat.signedUsdt(it)) } ?: "—",
                    style = MaterialTheme.typography.titleMedium.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = plColor(summary.unrealized)
                )
                if (summary.unrealized != null) {
                    PlPill(summary.unrealizedPercent, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Metric(
                    label = stringResource(R.string.portfolio_invested),
                    value = summary.invested?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                if (!PortfolioFormat.isZero(summary.realized)) {
                    Metric(
                        label = stringResource(R.string.portfolio_realized),
                        value = maskAmount(PortfolioFormat.signedUsdt(summary.realized)),
                        valueColor = plColor(summary.realized),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Hinweise, warum ± fehlt
            if (summary.costMissing) {
                PortfolioHint(stringResource(R.string.portfolio_price_missing_hint))
            }
            if (summary.missingCurrentPrices.isNotEmpty()) {
                PortfolioHint(stringResource(R.string.portfolio_current_missing, summary.missingCurrentPrices.joinToString(", ")))
            }
            if (updatedAt > 0) {
                Text(
                    stringResource(R.string.portfolio_updated, PriceFormat.time(updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

/**
 * Gesamtwert gross (displaySmall, Betragsschrift) mit rollenden Ziffern. Passt er nicht
 * in die Breite, wird die Schrift verkleinert (höchstens auf die Hälfte, wie iOS
 * `minimumScaleFactor(0.5)`).
 */
@Composable
private fun FittedTotal(text: String, value: Double, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val base = MaterialTheme.typography.display.amountNumbers().copy(fontWeight = FontWeight.SemiBold)
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val style = remember(text, maxPx, base) {
            val width = measurer.measure(text, base, maxLines = 1, softWrap = false).size.width
            if (!constraints.hasBoundedWidth || width <= maxPx || width == 0) base
            else base.copy(fontSize = base.fontSize * (maxPx.toFloat() / width * 0.98f).coerceAtLeast(0.5f))
        }
        RollingNumberText(text = text, value = value, style = style)
    }
}

/** «heute ▲ +1.23%» als Pille in der Kursfarbe; vorgelesen «heute gestiegen um 1.23%». */
@Composable
private fun TodayPill(percent: Double, modifier: Modifier = Modifier) {
    val color = plColor(percent)
    val arrow = if (PortfolioFormat.isZero(percent)) "" else PriceFormat.changeArrow(percent)
    val value = PortfolioFormat.signedPercent(percent).let { if (arrow.isEmpty()) it else "$arrow $it" }
    RollingNumberText(
        text = stringResource(R.string.widget_portfolio_today, value),
        value = percent,
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        contentDescription = stringResource(R.string.widget_portfolio_today, A11yText.change(LocalContext.current, percent)),
        modifier = modifier.changePill(color)
    )
}
