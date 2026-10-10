@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.components.ListSegment
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ReadableMaxWidth
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat

/**
 * Zeile je Coin: Plakette, Symbol, Menge, Ø/aktuell, Wert und ± %. Darunter klein die
 * Kursänderung [dayChange] über die %-Basis [basis] (z. B. «24h ▲ +2.1 %»), sobald es eine gibt
 * — ersetzt die frühere Karte «Grösste Bewegungen».
 */
@Composable
internal fun CoinRow(
    position: CoinPosition,
    onClick: () -> Unit,
    dayChange: Double? = null,
    basis: ChangeBasis? = null,
    onDelete: (() -> Unit)? = null,
    txCount: Int = 0,
    /** Form je nach Platz in der Liste ([ListSegment.shape]); Positionen wirken wie eine Einheit. */
    shape: Shape = MaterialTheme.shapes.medium,
) {
    // Screenreader: Löschen (sonst nur per Wischen) als eigene Aktion der Zeile
    val deleteLabel = stringResource(R.string.action_delete)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (onDelete != null) Modifier.semantics {
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                } else Modifier
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Gleicher Innenabstand und gleich grosses Logo wie in der Merkliste
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            // Abstand an der Plakette: Logos aus → keine Plakette, kein Einzug
            CoinBadge(position.coin, size = ListSegment.Logo, portfolio = true, modifier = Modifier.padding(end = 8.dp))
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
                // Menge, daneben die Anzahl Transaktionen als kleine Zahl («1.2 BTC  3»)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        maskAmount(PortfolioFormat.amount(position.holdings, position.coin)),
                        style = MaterialTheme.typography.bodySmall.amountNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Grosse Schrift: Menge bricht um statt abgeschnitten zu werden
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (txCount > 0) TxCountBadge(txCount)
                }
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
                if (dayChange != null && basis != null && dayChange.isFinite()) {
                    val context = LocalContext.current
                    val arrow = dayChange.takeUnless { PortfolioFormat.isZero(it) }
                        ?.let { PriceFormat.changeArrow(it) }.orEmpty()
                    val percent = PortfolioFormat.signedPercent(dayChange)
                    val spoken = ChangeBasisText.spoken(context, basis, dayChange)
                    Text(
                        ChangeBasisText.shortLabel(context, basis) + " " + (if (arrow.isEmpty()) percent else "$arrow $percent"),
                        style = MaterialTheme.typography.labelSmall.amountNumbers(),
                        color = plColor(dayChange),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clearAndSetSemantics { contentDescription = spoken }
                    )
                }
            }
        }
    }
}

/**
 * Anzahl Transaktionen eines Coins: nur die Zahl, klein und grau in einer Kapsel. Screenreader:
 * «Transaktionen: 3».
 */
@Composable
private fun TxCountBadge(count: Int) {
    val spoken = stringResource(R.string.portfolio_transactions) + ": " + LocaleNumbers.integer(count)
    Text(
        LocaleNumbers.integer(count),
        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(start = Spacing.xs)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    )
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
            // Beispiel mit Kaufkurs in USDT — wie das Preisfeld beim Erfassen (sonst landet
            // «58 000» in CHF gedacht als USDT im Portfolio)
            Text(
                stringResource(
                    R.string.portfolio_empty_example,
                    PriceFormat.priceWithCurrency(EXAMPLE_BUY_PRICE, PortfolioFormat.USDT)
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
