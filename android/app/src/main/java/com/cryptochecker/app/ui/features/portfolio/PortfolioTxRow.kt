// Eine Transaktionszeile der Coin-Detailseite mit Buchungs- und Tausch-Text.
// Eigene Datei, weil Zeile und Texte zusammengehören und die Detailseite sonst zu lang wird
// (wie `PortfolioTxRow.swift`).
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.tabularNumbers

/** Transaktion: Art und Datum, Menge × Preis, Summe; Tausch: «Tausch in ETH» / «Tausch von BTC», «0.1 BTC → 1.6 ETH», Wert. */
@Composable
internal fun TxRow(tx: PortfolioTxEntity, coin: String, onClick: () -> Unit, onDelete: () -> Unit) {
    // Screenreader: Löschen (sonst nur per Wischen) als eigene Aktion der Zeile
    val deleteLabel = stringResource(R.string.action_delete)
    // Zugänge grün, Abgänge rot, Umbuchung neutral
    val typeColor = when {
        tx.type.isInflow -> PriceColors.up
        tx.type.isOutflow -> PriceColors.down
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true }) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        swapLabel(tx, coin) ?: stringResource(tx.type.labelRes()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = typeColor
                    )
                    Text(
                        " · " + PortfolioFormat.date(tx.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    swapLine(tx)?.let { maskAmount(it) } ?: (maskAmount(PortfolioFormat.amount(tx.amount, tx.coin)) + " × " +
                        (tx.priceUsdt?.let { PortfolioFormat.price(it.toDouble()) }
                            ?: stringResource(R.string.portfolio_price_missing))),
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Gebühr und Börse: «Gebühr 1.20 USDT · Binance», klein und grau
                ledgerLine(tx)?.let { line ->
                    Text(
                        maskAmount(line),
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                tx.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Tausch: Wert des Tauschs (unbekannt «—»)
            val total = if (tx.type == PortfolioTxType.SWAP) tx.toTrade().swapValue else tx.priceUsdt?.let { it * tx.amount }
            Text(
                withSmallQuote(total?.let { maskAmount(PortfolioFormat.usdt(it.toDouble())) } ?: "—"),
                style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** «Gebühr 1.20 USDT · Binance», «Gebühr 0.0001 BTC», «Binance»; null ohne beides. */
@Composable
private fun ledgerLine(tx: PortfolioTxEntity): String? {
    val fee = tx.fee?.takeIf { it.signum() > 0 }?.let { amount ->
        val text = when (PortfolioFeeUnit.fromName(tx.feeUnit)) {
            PortfolioFeeUnit.QUOTE -> PortfolioFormat.amount(amount, PortfolioFormat.USDT)
            PortfolioFeeUnit.COIN -> PortfolioFormat.amount(amount, tx.coin)
        }
        stringResource(R.string.ledger_fee_line, text)
    }
    // Gebühr in einem dritten Coin («Gebühr 0.01 BNB»)
    val thirdFee = tx.feeCoin?.takeIf { it.isNotBlank() }?.let { feeCoin ->
        tx.feeCoinAmount?.takeIf { it.signum() > 0 }?.let { stringResource(R.string.ledger_fee_line, PortfolioFormat.amount(it, feeCoin)) }
    }
    // Umbuchung: «Binance → Ledger» (fehlt eine Seite: «—»); Schenkung an Ehe-/Lebenspartner (nur bei Schenkungen gesetzt)
    val route = if (tx.giftToSpouse) {
        stringResource(R.string.portfolio_tx_gift_spouse)
    } else if (!tx.transferFrom.isNullOrBlank() || !tx.transferTo.isNullOrBlank()) {
        "${tx.transferFrom?.takeIf { it.isNotBlank() } ?: "—"} → ${tx.transferTo?.takeIf { it.isNotBlank() } ?: "—"}"
    } else null
    val parts = listOfNotNull(fee, thirdFee, route, tx.exchange?.takeIf { it.isNotBlank() })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Tausch aus Sicht von [coin]: abgegeben «Tausch in ETH», erhalten «Tausch von BTC»; andere Arten null. */
@Composable
private fun swapLabel(tx: PortfolioTxEntity, coin: String): String? {
    if (tx.type != PortfolioTxType.SWAP) return null
    val to = tx.toCoin?.takeIf { it.isNotBlank() } ?: return null
    return if (PortfolioCalculator.normalizeCoin(to) == coin) stringResource(R.string.portfolio_tx_swap_from, tx.coin)
    else stringResource(R.string.portfolio_tx_swap_to, to)
}

/** Tausch: «0.1 BTC → 1.6 ETH»; andere Arten null. */
private fun swapLine(tx: PortfolioTxEntity): String? {
    if (tx.type != PortfolioTxType.SWAP) return null
    val to = tx.toCoin?.takeIf { it.isNotBlank() } ?: return null
    val received = tx.toAmount ?: return null
    return PortfolioFormat.amount(tx.amount, tx.coin) + " → " + PortfolioFormat.amount(received, to)
}
