// Positionskarte der Coin-Detailseite (Kennzahlen des Coins in zwei Spalten) und die offenen Lose darunter.
// Eigene Datei, weil die Karte eine eigenständige Anzeige ist; die Detailseite ordnet nur an
// (wie `PortfolioPositionCard.swift`).
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.portfolio

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.PortfolioOpenLot
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers

/** Kennzahlen eines Coins in zwei Spalten. */
@Composable
internal fun PositionCard(
    p: CoinPosition,
    addressAmount: java.math.BigDecimal? = null,
    overlap: Boolean = false,
    matches: List<PortfolioTrade> = emptyList(),
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoinBadge(p.coin, size = 44.dp, portfolio = true, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.portfolio_value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    withSmallQuote(p.value?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—"),
                    style = MaterialTheme.typography.headlineSmall.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Metric(
                label = stringResource(R.string.portfolio_holdings),
                value = maskAmount(PortfolioFormat.amount(p.holdingsExact, p.coin)),
                smallQuote = false,
                modifier = Modifier.weight(1f)
            )
            Column(modifier = Modifier.weight(1f)) {
                // FIFO: Ø über die offenen Posten; Ø-Methode: gleitender Durchschnitt
                Metric(
                    label = stringResource(
                        if (p.method.isFifo) R.string.portfolio_avg_open_lots else R.string.portfolio_avg_price
                    ),
                    value = PortfolioFormat.price(p.avgCost),
                )
                // Ø nur über den Teil mit Kaufpreis: Menge nennen («Ø von 0.5 BTC mit Kaufpreis»)
                if (p.avgCostPartial) {
                    Text(
                        stringResource(
                            R.string.portfolio_avg_partial,
                            maskAmount(PortfolioFormat.amount(p.pricedHoldingsExact, p.coin))
                        ),
                        style = MaterialTheme.typography.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        // Teil aus beobachteten Adressen («Aus Adresse · nicht in «Seit Kauf»: 0.5 BTC»)
        if (addressAmount != null) {
            Text(
                stringResource(R.string.portfolio_address_not_since_buy, maskAmount(PortfolioFormat.amount(addressAmount, p.coin))),
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            // Adresse und eigene Transaktionen im selben Coin: werden addiert — Hinweis statt stiller Doppelzählung;
            // gleiche Menge wie eine eigene Einzahlung/Umbuchung ([WatchedAddressRules.transferMatchesFor]): deutlicher
            if (matches.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.portfolio_address_overlap_tx,
                        maskAmount(matches.joinToString(", ") { PortfolioFormat.amount(it.amount, p.coin) }),
                    ),
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else if (overlap) {
                Text(
                    stringResource(R.string.portfolio_address_overlap, p.coin),
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Metric(
                label = stringResource(R.string.portfolio_current_price),
                value = PortfolioFormat.price(p.currentPrice),
                modifier = Modifier.weight(1f)
            )
            Metric(
                // Immer «Einstand» — die Methode steht in den Einstellungen und im Hinweis darunter
                label = stringResource(R.string.portfolio_invested),
                value = p.costBasis?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Metric(
                    label = stringResource(R.string.portfolio_unrealized),
                    value = p.unrealized?.let { maskAmount(PortfolioFormat.signedUsdt(it)) } ?: "—",
                    valueTone = plTone(p.unrealized)
                )
                if (p.unrealized != null) {
                    PlPill(p.unrealizedPercent, modifier = Modifier.padding(top = 4.dp))
                }
                // Nur der Teil mit Kaufpreis zählt: «ohne 0.5 BTC ohne Kaufpreis»
                if (p.unrealizedPartial) {
                    Text(
                        stringResource(
                            R.string.portfolio_unrealized_without_unpriced,
                            maskAmount(PortfolioFormat.amount(p.unpricedHoldingsExact, p.coin))
                        ),
                        style = MaterialTheme.typography.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (PortfolioFormat.showRealized(p.realized, p.realizedIncomplete)) {
                Metric(
                    label = stringResource(R.string.portfolio_realized),
                    value = maskAmount(PortfolioFormat.realizedText(p.realized, p.realizedIncomplete, stringResource(R.string.portfolio_export_incomplete))),
                    valueTone = plTone(p.realized),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Wie Einstand und Ø gerechnet werden (nach der gewählten Methode)
        PortfolioHint(
            stringResource(if (p.method.isFifo) R.string.portfolio_fifo_hint else R.string.portfolio_avg_price_hint)
        )
        if (p.priceMissing) {
            PortfolioHint(stringResource(R.string.portfolio_price_missing_hint))
        }
        // Netzwerkgebühr, deren Einstand keinen bezahlten Posten mehr fand (weder Einstand noch realisiert)
        if (p.lostFeeCostUsdt.signum() != 0) {
            PortfolioHint(stringResource(R.string.portfolio_lost_fee_cost, maskAmount(PortfolioFormat.usdt(p.lostFeeCostUsdt.toDouble()))))
        }
        // Gebührenanteil auf Bestand ohne Kaufpreis: offener Posten statt still entfallen (Einstand unbekannt)
        if (p.unassignedFeeUsdt.signum() != 0) {
            PortfolioHint(stringResource(R.string.portfolio_unassigned_fee, maskAmount(PortfolioFormat.usdt(p.unassignedFeeUsdt.toDouble()))))
        }
        if (p.oversold) {
            Text(
                stringResource(R.string.portfolio_oversold),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * «Offene Kauf-Posten» (zugeklappt): FIFO je Posten Kaufdatum, Restmenge, Rest-Einstand (und je Coin), Wallet (FIFO je
 * Wallet), Posten ohne Kaufpreis markiert; Ø die zusammengefasste(n) Zeile(n).
 */
@Composable
internal fun OpenLotsSection(lots: List<PortfolioOpenLot>, coin: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        ExpandToggleButton(
            text = stringResource(R.string.portfolio_open_lots, lots.size),
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            AppCard(padding = CardPadding.List) {
                lots.forEach { lot -> OpenLotRow(lot, coin) }
            }
        }
    }
}

@Composable
private fun OpenLotRow(lot: PortfolioOpenLot, coin: String) {
    val cost = lot.costUsdt
    val title = when {
        lot.aggregated -> stringResource(if (cost != null) R.string.portfolio_open_lots_avg else R.string.portfolio_open_lots_no_cost)
        else -> lot.acquiredTime?.let { PortfolioFormat.date(it) } ?: "—"
    }
    val walletText = lot.wallet?.let { w -> stringResource(R.string.tax_export_wallet, w.ifBlank { stringResource(R.string.tax_export_wallet_unspecified) }) }
    val costText = if (cost != null) {
        val perCoin = if (lot.amount.signum() > 0) cost.divide(lot.amount, java.math.MathContext.DECIMAL128) else null
        maskAmount(PortfolioFormat.usdt(cost.toDouble())) + (perCoin?.let { " · Ø " + PortfolioFormat.price(it.toDouble()) } ?: "")
    } else stringResource(R.string.portfolio_open_lots_without_price)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.tabularNumbers(), fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(costText, walletText).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = if (cost == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            maskAmount(PortfolioFormat.amount(lot.amount, coin)),
            style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
