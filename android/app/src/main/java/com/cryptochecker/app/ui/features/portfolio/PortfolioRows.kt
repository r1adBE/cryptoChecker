@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ReadableMaxWidth
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat

/** Zeile je Coin: Plakette, Symbol, Menge, Ø/aktuell, Wert und ± %. */
@Composable
internal fun CoinRow(position: CoinPosition, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp)
        ) {
            // Abstand an der Plakette: Logos aus → keine Plakette, kein Einzug
            CoinBadge(position.coin, portfolio = true, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        position.coin,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (position.oversold) {
                        Icon(
                            painterResource(R.drawable.ic_error),
                            contentDescription = stringResource(R.string.portfolio_oversold),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = Spacing.xs).size(14.dp)
                        )
                    }
                }
                Text(
                    maskAmount(PortfolioFormat.amount(position.holdings, position.coin)),
                    style = MaterialTheme.typography.bodySmall.amountNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Grosse Schrift: Menge bricht um statt abgeschnitten zu werden
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.portfolio_avg_and_now,
                        PriceFormat.price(position.avgCost),
                        PriceFormat.price(position.currentPrice)
                    ),
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    position.value?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (position.priceMissing) {
                    Text(
                        stringResource(R.string.portfolio_price_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else {
                    PlPill(position.unrealizedPercent, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** Geschlossene Position: nur der realisierte Gewinn/Verlust. */
@Composable
internal fun ClosedRow(position: CoinPosition, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 8.dp)
    ) {
        CoinBadge(position.coin, size = 32.dp, portfolio = true, modifier = Modifier.padding(end = 12.dp))
        Text(
            position.coin,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            maskAmount(PortfolioFormat.signedUsdt(position.realized)),
            style = MaterialTheme.typography.bodyMedium.amountNumbers(),
            color = plColor(position.realized)
        )
    }
}

/** Kaufkurs im Beispiel des leeren Portfolios. */
private const val EXAMPLE_BUY_PRICE = 58_000.0

@Composable
internal fun EmptyPortfolio(modifier: Modifier, currency: String, onAdd: () -> Unit) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = ReadableMaxWidth)
        ) {
            Icon(
                painterResource(R.drawable.ic_portfolio),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                stringResource(R.string.portfolio_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                stringResource(R.string.portfolio_empty_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            // Beispiel mit Kaufkurs in der gewählten Umrechnungswährung
            Text(
                stringResource(
                    R.string.portfolio_empty_example,
                    PriceFormat.priceWithCurrency(EXAMPLE_BUY_PRICE, currency)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = onAdd, modifier = Modifier.padding(top = Spacing.lg)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.portfolio_empty_action), modifier = Modifier.padding(start = 8.dp))
            }
            PortfolioDisclaimer()
        }
    }
}
