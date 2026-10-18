// Eingabefelder des Transaktions-Blatts: Buchungsangaben (Börse, Wallets von/nach), Gebühr in einem dritten Coin,
// ursprüngliches Kaufdatum, «Eingabe in: USDT | CHF» und die erhaltene Seite eines Tauschs.
// Eigene Datei, weil Zustand, Prüfung und Speichern in `TxFormState`/`PortfolioTxForm.check` liegen und die Felder für sich lesbar sein
// sollen (wie `PortfolioTxFields.swift` / `PortfolioTxLedgerFields.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers

/**
 * Von/Nach einer Umbuchung: freier Text (Börse oder Wallet). Build 46: Vorschläge als Chips — im leeren,
 * fokussierten Feld die selbst benutzten Namen [used], beim Tippen dazu die Börsen der App [suggestions]
 * ([PortfolioWalletNames.suggestions]); bei einem fast gleichen bekannten Namen («Binanse») der Hinweis
 * «Meintest du Binance?» zum Antippen — bei FIFO je Wallet wäre jede andere Schreibweise eine eigene Wallet.
 */
@Composable
internal fun RouteField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    used: List<String>,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        supportingText = hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
    )
    val query = value.trim()
    val matches = remember(used, suggestions, query) {
        com.cryptochecker.app.domain.portfolio.PortfolioWalletNames.suggestions(used, suggestions, query)
    }
    val nearName = remember(used, suggestions, query) {
        com.cryptochecker.app.domain.portfolio.PortfolioWalletNames.nearDuplicate(
            query, com.cryptochecker.app.domain.portfolio.PortfolioWalletNames.known(used + suggestions)
        )
    }
    if (nearName != null) {
        // «Meintest du Binance?» — antippen übernimmt den bekannten Namen
        AssistChip(
            onClick = { onValue(nearName); focus.clearFocus() },
            label = {
                Text(
                    stringResource(R.string.portfolio_wallet_did_you_mean, nearName),
                    color = AppColors.warningText,
                    maxLines = 2,
                )
            },
            leadingIcon = {
                // Warnfarbe trägt Bedeutung: für den Screenreader «Warnung» vor dem Vorschlag (wie iOS)
                Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.a11y_warning),
                    tint = AppColors.warning, modifier = Modifier.size(18.dp))
            },
        )
    }
    if (matches.isNotEmpty() && (query.isNotEmpty() || focused)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(matches, key = { it }) { name ->
                AssistChip(onClick = { onValue(name); focus.clearFocus() }, label = { Text(name, maxLines = 1) })
            }
        }
    }
}

/** Gebühr in einem dritten Coin (z. B. BNB): Coin, Menge, Preis je Gebühr-Coin (optional — leer = unvollständig). */
@Composable
internal fun ThirdFeeFields(
    feeCoin: String,
    onFeeCoin: (String) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    price: String,
    onPrice: (String) -> Unit,
    state: PortfolioTxForm.ThirdFee,
    /** Tausch, Gebühr im erhaltenen Coin: kein Preisfeld, nur dieser Hinweis. */
    receivedCoinNote: String? = null,
    /** Tausch: Gebühr im erhaltenen Coin ≥ erhaltene Menge (Fehler). */
    consumes: Boolean = false,
) {
    val invalid = state == PortfolioTxForm.ThirdFee.INVALID || consumes
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = feeCoin,
                onValueChange = onFeeCoin,
                label = { Text(stringResource(R.string.portfolio_tx_third_fee_coin)) },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = amount,
                onValueChange = onAmount,
                label = { Text(stringResource(R.string.portfolio_tx_third_fee_amount)) },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        if (receivedCoinNote != null) {
            Text(
                when {
                    consumes -> stringResource(R.string.portfolio_tx_fee_consumes_amount)
                    invalid -> stringResource(R.string.portfolio_invalid)
                    else -> receivedCoinNote
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            return@Column
        }
        OutlinedTextField(
            value = price,
            onValueChange = onPrice,
            label = { Text(stringResource(R.string.portfolio_tx_third_fee_price)) },
            suffix = { Text(PortfolioFormat.USDT) },
            singleLine = true,
            isError = invalid,
            supportingText = when (state) {
                PortfolioTxForm.ThirdFee.INVALID -> { { Text(stringResource(R.string.portfolio_invalid)) } }
                PortfolioTxForm.ThirdFee.INCOMPLETE -> { { Text(stringResource(R.string.portfolio_tx_third_fee_incomplete)) } }
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

/**
 * Feld «Ursprüngliches Kaufdatum (optional)» einer Einzahlung wie das Datumsfeld (antippen → Datumswahl), mit
 * Leeren-Knopf, darunter die Erklärung «Für die Haltedauer im Steuer-Export».
 */
@Composable
internal fun AcquiredDateField(acquiredTime: Long?, onPick: () -> Unit, onClear: () -> Unit) {
    Surface(
        onClick = onPick,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = if (acquiredTime != null) 4.dp else 16.dp)
                .heightIn(min = 56.dp)
        ) {
            Text(
                stringResource(R.string.portfolio_tx_acquired_date),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.sm)
            )
            if (acquiredTime != null) {
                Text(
                    PortfolioFormat.date(acquiredTime),
                    style = MaterialTheme.typography.bodyLarge.tabularNumbers()
                )
                IconButton(onClick = onClear) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp)
                )
            }
        }
    }
    Text(
        stringResource(R.string.portfolio_tx_acquired_date_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
    )
}

/** «Eingabe in: USDT | CHF» — Preis/Wert in USDT oder in der Anzeigewährung (gespeichert wird USDT). */
@Composable
internal fun EntryCurrencyRow(
    currency: String,
    fiat: Boolean,
    fiatEnabled: Boolean,
    onUsdt: () -> Unit,
    onFiat: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.portfolio_tx_entry_currency),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !fiat,
                onClick = onUsdt,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {}
            ) { Text(PortfolioFormat.USDT, maxLines = 1) }
            SegmentedButton(
                selected = fiat,
                onClick = onFiat,
                enabled = fiatEnabled || fiat,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {}
            ) { Text(currency, maxLines = 1) }
        }
    }
}

/** Tausch: erhaltener Coin (mit Suche) und erhaltene Menge. */
@Composable
internal fun SwapReceivedFields(
    toCoin: String,
    onToCoin: (String) -> Unit,
    toCoinError: String?,
    coins: List<String>,
    toAmount: String,
    onToAmount: (String) -> Unit,
    toAmountError: Boolean,
) {
    val focus = LocalFocusManager.current
    val symbol = PortfolioCalculator.normalizeCoin(toCoin)
    OutlinedTextField(
        value = toCoin,
        onValueChange = onToCoin,
        label = { Text(stringResource(R.string.portfolio_tx_swap_received_coin)) },
        placeholder = { Text(stringResource(R.string.portfolio_coin_search)) },
        singleLine = true,
        isError = toCoinError != null,
        supportingText = toCoinError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
    val matches = remember(coins, symbol) { matchCoins(coins, symbol) }
    if (matches.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(matches, key = { it }) { match ->
                AssistChip(onClick = { onToCoin(match); focus.clearFocus() }, label = { Text(match, maxLines = 1) })
            }
        }
    }
    val valid = symbol.length in 1..15 && symbol.all { it.isLetterOrDigit() }
    OutlinedTextField(
        value = toAmount,
        onValueChange = onToAmount,
        label = { Text(stringResource(R.string.portfolio_tx_swap_received_amount)) },
        suffix = if (valid) { { Text(symbol) } } else null,
        singleLine = true,
        isError = toAmountError,
        supportingText = if (toAmountError) { { Text(stringResource(R.string.portfolio_invalid)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
}
