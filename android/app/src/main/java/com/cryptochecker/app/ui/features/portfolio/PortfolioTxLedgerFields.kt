// Gebühr und Börse im Transaktions-Blatt (wie `PortfolioTxLedgerFields.swift`): Gebühr je Art mit Einheit USDT | Coin
// und die Spot-Börse der Bewertung. Eigene Datei, weil `PortfolioTxFields` sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid.Field

/**
 * Gebühr und Börse je Art ([PortfolioTxForm.Fields]):
 *  - Kauf/Verkauf: Gebühr mit Einheit USDT | Coin. Gebühr in USDT erhöht beim Kauf den Einstand und
 *    verringert beim Verkauf den Erlös; Gebühr im Coin verringert die Menge.
 *  - Auszahlung: Gebühr nur im Coin; Umbuchung: Netzwerkgebühr im Coin (senkt nur die Menge).
 *  - Börse (nicht bei der Umbuchung): eine Spot-Börse der App ([exchanges]); der Coin wird dann mit ihrem
 *    Kurs bewertet (sonst Binance, PortfolioExchangeRoute). Ein früher frei getippter Name bleibt wählbar.
 */
@Composable
internal fun LedgerFields(
    fields: PortfolioTxForm.Fields,
    type: PortfolioTxType,
    feeText: String,
    onFeeText: (String) -> Unit,
    feeValid: Boolean,
    /** Gebühr ≥ erhaltene Menge ([PortfolioTxForm.feeConsumesReceived]): Fehler am Feld. */
    feeConsumes: Boolean,
    feeUnit: PortfolioFeeUnit,
    onFeeUnit: (PortfolioFeeUnit) -> Unit,
    coin: String,
    exchange: String,
    exchanges: List<String>,
    onExchange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (fields.feeWithUnit || fields.coinFeeOnly) {
            OutlinedTextField(
                value = feeText,
                onValueChange = onFeeText,
                label = {
                    Text(
                        stringResource(
                            when {
                                fields.feeWithUnit -> R.string.ledger_fee
                                type == PortfolioTxType.TRANSFER -> R.string.portfolio_tx_network_fee
                                else -> R.string.portfolio_tx_coin_fee
                            }
                        )
                    )
                },
                suffix = if (fields.coinFeeOnly && coin.isNotEmpty()) { { Text(coin) } } else null,
                singleLine = true,
                isError = !feeValid || feeConsumes,
                supportingText = when {
                    !feeValid -> { { Text(stringResource(R.string.portfolio_tx_price_invalid)) } }
                    feeConsumes -> { { Text(stringResource(R.string.portfolio_tx_fee_consumes_amount)) } }
                    fields.feeWithUnit -> { { Text(stringResource(R.string.ledger_fee_hint)) } }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().txFocusTarget(LocalTxFocusTargets.current, Field.FEE)
            )
        }
        if (fields.feeWithUnit) {
            // Einheit der Gebühr: USDT | Coin
            val units = PortfolioFeeUnit.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                units.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = feeUnit == option,
                        onClick = { onFeeUnit(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = units.size),
                        icon = {}
                    ) {
                        val label = when (option) {
                            PortfolioFeeUnit.QUOTE -> PortfolioFormat.USDT
                            PortfolioFeeUnit.COIN -> coin.ifEmpty { stringResource(R.string.ledger_fee_coin) }
                        }
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
        if (fields.exchange) {
            // Börse: «Keine (Binance)», ein früher frei getippter Name (falls keiner der Liste) und die Spot-Börsen
            val none = stringResource(R.string.ledger_exchange_none)
            val current = exchange.trim()
            val legacy = current.takeIf { c -> c.isNotEmpty() && exchanges.none { it.equals(c, ignoreCase = true) } }
            val options = listOf(none) + listOfNotNull(legacy) + exchanges
            val selected = when {
                current.isEmpty() -> 0
                legacy != null -> 1
                else -> options.indexOfFirst { it.equals(current, ignoreCase = true) }.coerceAtLeast(0)
            }
            ComboBox(
                selectedIndex = selected,
                itemList = options,
                onValueChange = { index -> onExchange(if (index <= 0) "" else options[index]) },
                label = stringResource(R.string.ledger_exchange),
                searchable = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Text(
                stringResource(R.string.ledger_exchange_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}
