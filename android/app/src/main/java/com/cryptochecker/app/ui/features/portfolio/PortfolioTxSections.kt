// Abschnitte des Transaktions-Blatts (`PortfolioTxSheet`) in der Reihenfolge des Blatts: Art, Coin, Datum, Menge,
// erhaltene Seite eines Tauschs und Eingabewährung; ab Preis/Wert in `PortfolioTxValueSections`. Jeder Abschnitt liest [TxFormState] (Eingaben) und [TxFormDerived] (Prüfung dieses Durchgangs) und setzt
// Felder direkt im Zustand; sie legen kein eigenes Layout um sich (die Spalte des Blatts ordnet sie an).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid.Field

/** Art als eine kompakte Wahl «Art: Kauf ▾» (die übrigen Arten im Menü) samt Erklärung. */
@Composable
internal fun TxTypeSection(state: TxFormState) {
    var typeMenu by remember { mutableStateOf(false) }
    val type = state.type
    Box(Modifier.padding(top = 4.dp)) {
        ExpandToggleButton(
            text = stringResource(R.string.portfolio_tx_type_select, stringResource(type.labelRes())),
            expanded = typeMenu,
            onClick = { typeMenu = true },
        )
        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
            PortfolioTxForm.TYPES.forEach { option ->
                // Gewählte Art mit Häkchen (wie das Menü in iOS)
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = { state.type = option; typeMenu = false },
                    leadingIcon = if (option == type) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else null,
                )
            }
        }
    }
    type.explainRes()?.let { res ->
        Text(
            stringResource(res),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Auszahlung: Verschenkt oder damit bezahlt? Je nach Land steuerlich ein Verkauf (eigene Arten)
    if (type == PortfolioTxType.WITHDRAWAL) {
        Text(
            stringResource(R.string.portfolio_tx_withdrawal_gift_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Coin mit Suche: Treffer als Chips; [onPicked] nach der Wahl eines Treffers (Tastatur schliessen). */
@Composable
internal fun TxCoinSection(state: TxFormState, d: TxFormDerived, coins: List<String>, onPicked: () -> Unit) {
    val tried = state.tried
    OutlinedTextField(
        value = state.coinQuery,
        onValueChange = { state.coinQuery = it.take(15) },
        label = { Text(stringResource(if (d.isSwap) R.string.portfolio_tx_swap_given_coin else R.string.portfolio_coin)) },
        placeholder = { Text(stringResource(R.string.portfolio_coin_search)) },
        singleLine = true,
        isError = tried && !d.coinValid,
        supportingText = if (tried && !d.coinValid) {
            { Text(stringResource(R.string.portfolio_coin_invalid)) }
        } else null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).txFocusTarget(LocalTxFocusTargets.current, Field.COIN)
    )
    val coin = d.coin
    val matches = remember(coins, coin) { matchCoins(coins, coin) }
    if (matches.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(matches, key = { it }) { symbol ->
                AssistChip(
                    onClick = { state.coinQuery = symbol; onPicked() },
                    label = { Text(symbol, maxLines = 1) }
                )
            }
        }
    }
}

/** Datum der Transaktion (Wahl über [onPickDate]); Einzahlung: ursprüngliches Kaufdatum (optional) — Haltedauer im Steuer-Export. */
@Composable
internal fun TxDateSection(state: TxFormState, onPickDate: () -> Unit, onPickAcquired: () -> Unit) {
    Surface(
        onClick = onPickDate,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.lg)
        ) {
            Text(
                stringResource(R.string.portfolio_tx_date),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                PortfolioFormat.date(state.time),
                style = MaterialTheme.typography.bodyLarge.tabularNumbers()
            )
            Icon(
                painterResource(R.drawable.ic_edit),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(18.dp)
            )
        }
    }
    if (state.type == PortfolioTxType.DEPOSIT) {
        AcquiredDateField(
            acquiredTime = state.acquiredTime,
            onPick = onPickAcquired,
            onClear = { state.acquiredTime = null },
        )
    }
}

/**
 * Menge; Abgang (Verkauf, Auszahlung, Umbuchung) + Gebühr im Coin über dem Bestand: Warnung (Speichern bleibt möglich).
 * FIFO je Wallet: auch der Bestand DIESER Wallet (Börse bzw. «Von» der Umbuchung) muss reichen.
 */
@Composable
internal fun TxAmountSection(state: TxFormState, d: TxFormDerived, viewModel: PortfolioViewModel) {
    val type = state.type
    val coin = d.coin
    val coinValid = d.coinValid
    val amount = d.amount
    val holdings = if (d.fields.outflow && coinValid) viewModel.holdingsOf(coin, state.initial.id) else null
    val exceeds = PortfolioTxForm.outflowExceeds(type, amount, d.fee, state.feeUnit, holdings)
    val walletName = (if (type == PortfolioTxType.TRANSFER) state.transferFrom else state.exchange).trim()
    val walletHoldings = if (d.fields.outflow && coinValid && !exceeds) viewModel.walletHoldingsOf(coin, walletName, state.initial.id) else null
    val walletExceeds = walletHoldings != null && PortfolioTxForm.outflowExceeds(type, amount, d.fee, state.feeUnit, walletHoldings)
    val amountError = (state.tried || state.amountText.isNotBlank()) && amount == null
    val amountMessage: String? = when {
        amountError -> stringResource(R.string.portfolio_invalid)
        walletExceeds -> stringResource(
            R.string.portfolio_sell_exceeds_wallet,
            PortfolioFormat.amount(walletHoldings, coin),
            walletName.ifEmpty { stringResource(R.string.tax_export_wallet_unspecified) },
        )
        exceeds && holdings != null -> stringResource(
            if (PortfolioTxForm.sellExceedsIncludesFee(d.fee, d.effectiveFeeUnit)) {
                R.string.portfolio_sell_exceeds_fee
            } else {
                R.string.portfolio_sell_exceeds
            },
            PortfolioFormat.amount(holdings, coin),
        )
        else -> null
    }
    OutlinedTextField(
        value = state.amountText,
        onValueChange = { state.amountText = it },
        label = { Text(stringResource(if (d.isSwap) R.string.portfolio_tx_swap_given_amount else R.string.portfolio_tx_amount)) },
        suffix = if (coinValid) { { Text(coin) } } else null,
        singleLine = true,
        isError = amountError || exceeds || walletExceeds,
        supportingText = if (amountMessage != null) { { Text(amountMessage) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).txFocusTarget(LocalTxFocusTargets.current, Field.AMOUNT)
    )
}

/** Tausch: erhaltener Coin und Menge. */
@Composable
internal fun TxSwapReceivedSection(state: TxFormState, d: TxFormDerived, coins: List<String>) {
    val tried = state.tried
    SwapReceivedFields(
        toCoin = state.toCoinQuery,
        onToCoin = { state.toCoinQuery = it.take(15) },
        toCoinError = when {
            !(tried || state.toCoinQuery.isNotBlank()) || d.toCoinValid -> null
            d.coinValid && d.toCoin == d.coin -> stringResource(R.string.portfolio_tx_swap_same_coin)
            else -> stringResource(R.string.portfolio_coin_invalid)
        },
        coins = coins,
        toAmount = state.toAmountText,
        onToAmount = { state.toAmountText = it },
        toAmountError = (tried || state.toAmountText.isNotBlank()) && d.toAmount == null,
    )
}

/** «Eingabe in: USDT | CHF» mit Hinweis, solange der Kurs lädt bzw. wenn es für den Tag keinen gibt. */
@Composable
internal fun TxEntryCurrencySection(state: TxFormState, d: TxFormDerived, currency: String) {
    val fxFailed = state.fxFailed(currency, d.day)
    EntryCurrencyRow(
        currency = currency,
        fiat = state.entryFiat,
        fiatEnabled = !fxFailed,
        onUsdt = { state.switchToUsdt(d) },
        onFiat = { state.switchToFiat() },
    )
    val fxNote = when {
        // Kein Kurs für diesen Tag: Anzeigewährung gesperrt, mit Hinweis
        fxFailed -> stringResource(R.string.portfolio_tx_fiat_no_fx)
        !state.entryFiat -> null
        state.pendingToFiat || d.fxRate == null -> stringResource(R.string.portfolio_tx_fiat_loading)
        else -> null
    }
    fxNote?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (fxFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        )
    }
}
