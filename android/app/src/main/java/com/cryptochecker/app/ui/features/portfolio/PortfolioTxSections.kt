// Abschnitte des Transaktions-Blatts (`PortfolioTxSheet`) in der Reihenfolge des Blatts: Art, Coin, Datum, Menge,
// erhaltene Seite eines Tauschs, Eingabewährung, Preis/Wert, Schalter je Art, Von/Nach, Notiz, Gebühr/Börse, Summe,
// Knöpfe. Jeder Abschnitt liest [TxFormState] (Eingaben) und [TxFormDerived] (Prüfung dieses Durchgangs) und setzt
// Felder direkt im Zustand; sie legen kein eigenes Layout um sich (die Spalte des Blatts ordnet sie an).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import java.math.BigDecimal
import java.time.ZoneId

/** Art als eine kompakte Wahl «Art: Kauf ▾» (Build 46; die übrigen Arten im Menü) samt Erklärung. */
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
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
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
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
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

/** K-2: «Eingabe in: USDT | CHF» mit Hinweis, solange der Kurs lädt bzw. wenn es für den Tag keinen gibt. */
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

/** Tausch: Wert des Tauschs (optional, gesamt); sonst Preis pro Coin in USDT bzw. in der Anzeigewährung (Umbuchung, Tausch: keiner). */
@Composable
internal fun TxPriceValueSection(state: TxFormState, d: TxFormDerived, currency: String) {
    val type = state.type
    val entryUnit = if (d.fiatActive) currency else PortfolioFormat.USDT
    val fxDay = state.entryFx?.second
    // «≈ 12’345.67 USDT zum Kurs vom 03.03.2025» unter einem Feld in der Anzeigewährung: Betrag und Kursdatum
    val convertedLine: (BigDecimal?, Boolean) -> Pair<String, String>? = { usdt, perCoin ->
        if (d.fiatActive && usdt != null && fxDay != null) {
            (if (perCoin) PortfolioFormat.price(usdt.toDouble()) else PortfolioFormat.usdt(usdt.toDouble())) to
                PortfolioFormat.date(fxDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } else null
    }

    if (d.isSwap) {
        val converted = convertedLine(d.swapValue, false)
        OutlinedTextField(
            value = state.swapValueText,
            onValueChange = { state.swapValueText = it },
            label = { Text(stringResource(R.string.portfolio_tx_swap_value)) },
            suffix = { Text(entryUnit) },
            singleLine = true,
            isError = !d.swapValueValid,
            supportingText = {
                Text(
                    when {
                        !d.swapValueValid -> stringResource(R.string.portfolio_invalid)
                        converted != null -> stringResource(R.string.portfolio_tx_fiat_converted, converted.first, converted.second)
                        else -> stringResource(R.string.portfolio_tx_swap_value_hint)
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }

    if (d.fields.price) {
        val receipt = type == PortfolioTxType.REWARD || type == PortfolioTxType.DEPOSIT
        // Verschenkt, Damit bezahlt: Marktwert je Coin (Damit bezahlt: Erlös = Menge × Wert, kein Geld erhalten)
        val gift = type == PortfolioTxType.GIFT || type == PortfolioTxType.PAYMENT
        val historicDay = state.priceHistoricDay
        val converted = convertedLine(d.price, true)
        OutlinedTextField(
            value = state.priceText,
            onValueChange = { state.onPriceText(it) },
            label = {
                Text(
                    stringResource(
                        when {
                            receipt -> R.string.portfolio_tx_value_received
                            gift -> R.string.portfolio_tx_market_value
                            else -> R.string.portfolio_tx_price
                        }
                    )
                )
            },
            suffix = { Text(entryUnit) },
            singleLine = true,
            isError = !d.priceValid,
            supportingText = {
                Text(
                    when {
                        // Anzeigewährung ohne (geladenen) Kurs: Hinweis steht schon über dem Feld
                        d.priceZero -> stringResource(R.string.portfolio_tx_price_zero)
                        !d.priceValid && d.fiatActive && d.fxRate == null -> stringResource(R.string.portfolio_tx_fiat_no_fx)
                        !d.priceValid -> stringResource(R.string.portfolio_tx_price_invalid)
                        converted != null -> stringResource(R.string.portfolio_tx_fiat_converted, converted.first, converted.second)
                        // Geprüfter Tagesschluss vorbelegt: so markiert
                        historicDay != null -> stringResource(R.string.portfolio_tx_price_historic, historicDay)
                        receipt -> stringResource(R.string.portfolio_tx_price_receipt_hint)
                        // Ohne Marktwert: zum Tagesschluss geschätzt (PortfolioLedger.valueAtDayClose)
                        gift -> stringResource(R.string.portfolio_tx_gift_price_hint)
                        // Wert nur für die Verlaufsrechnung (PortfolioLedger.flow); der Abgang zählt immer zum Einstand
                        type == PortfolioTxType.WITHDRAWAL -> stringResource(R.string.portfolio_tx_withdrawal_price_hint)
                        // Anderer Tag: der heutige Kurs passt nicht — Kurs vom Kauftag eintragen
                        PortfolioTxForm.showPastPriceHint(state.isEdit, d.isToday) -> stringResource(R.string.portfolio_tx_price_past_hint)
                        else -> stringResource(R.string.portfolio_tx_price_hint)
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

/** Schalter je Art: Verschenkt an Ehe-/Lebenspartner, Belohnung als Zins. */
@Composable
internal fun TxSwitchesSection(state: TxFormState) {
    // Verschenkt an Ehe-/Lebenspartner: UK/CA ohne Gewinn und Verlust (TaxTemplate.spouseGiftNeutral)
    if (state.type == PortfolioTxType.GIFT) {
        SwitchRow(
            title = stringResource(R.string.portfolio_tx_gift_spouse),
            checked = state.giftToSpouse,
            onCheckedChange = { state.giftToSpouse = it },
            subtitle = stringResource(R.string.portfolio_tx_gift_spouse_hint),
        )
    }
    // Belohnung als Zins (Lending): AT zählt ihn beim Zufluss (Anschaffungskosten = Wert), Staking nicht
    if (state.type == PortfolioTxType.REWARD) {
        SwitchRow(
            title = stringResource(R.string.portfolio_tx_interest),
            checked = state.interest,
            onCheckedChange = { state.interest = it },
            subtitle = stringResource(R.string.portfolio_tx_interest_hint),
        )
    }
}

/** Umbuchung: von / nach (Vorschläge aus den eigenen Wallets [usedWallets] und den Börsen). */
@Composable
internal fun TxRouteSection(state: TxFormState, usedWallets: List<String>, exchanges: List<String>) {
    RouteField(
        value = state.transferFrom,
        onValue = { state.transferFrom = it.take(PortfolioTxForm.MAX_ROUTE) },
        label = stringResource(R.string.portfolio_tx_transfer_from),
        used = usedWallets,
        suggestions = exchanges,
        modifier = Modifier.padding(top = 4.dp),
    )
    RouteField(
        value = state.transferTo,
        onValue = { state.transferTo = it.take(PortfolioTxForm.MAX_ROUTE) },
        label = stringResource(R.string.portfolio_tx_transfer_to),
        used = usedWallets,
        suggestions = exchanges,
        hint = stringResource(R.string.portfolio_tx_transfer_to_hint),
    )
}

/** Notiz (höchstens [NOTE_MAX] Zeichen), Abstand [top] nach oben. */
@Composable
internal fun TxNoteField(state: TxFormState, top: Dp) {
    OutlinedTextField(
        value = state.note,
        onValueChange = { state.note = it.take(NOTE_MAX) },
        label = { Text(stringResource(R.string.portfolio_tx_note)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = top)
    )
}

/**
 * Gebühren und Börse (optional, zugeklappt); Belohnung/Einzahlung: nur die Börse. Gebühr in einem dritten Coin direkt
 * im Abschnitt (keine zweite Aufklapp-Ebene); Kauf ([noteInLedger]): auch die Notiz.
 */
@Composable
internal fun TxLedgerSection(state: TxFormState, d: TxFormDerived, exchanges: List<String>, noteInLedger: Boolean) {
    val fields = d.fields
    ExpandToggleButton(
        text = stringResource(
            when {
                noteInLedger -> R.string.portfolio_tx_more_buy
                fields.feeWithUnit || (fields.coinFeeOnly && fields.exchange) -> R.string.ledger_more_toggle
                d.hasFee -> R.string.portfolio_tx_fees_toggle
                else -> R.string.ledger_exchange
            }
        ),
        expanded = state.showLedger,
        onClick = { state.showLedger = !state.showLedger },
        modifier = Modifier.padding(top = 4.dp)
    )
    if (state.showLedger) {
        LedgerFields(
            fields = fields,
            type = state.type,
            feeText = state.feeText,
            onFeeText = { state.feeText = it },
            feeValid = d.feeValid,
            feeConsumes = d.feeConsumes && !d.isSwap,
            feeUnit = state.feeUnit,
            onFeeUnit = { state.feeUnit = it },
            coin = if (d.coinValid) d.coin else "",
            exchange = state.exchange,
            exchanges = exchanges,
            onExchange = { state.exchange = it.take(PortfolioJson.MAX_EXCHANGE) },
        )
        if (fields.thirdCoinFee) {
            Text(
                stringResource(R.string.portfolio_tx_third_fee_toggle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            ThirdFeeFields(
                feeCoin = state.feeCoinText,
                onFeeCoin = { state.feeCoinText = it.take(PortfolioJson.MAX_FEE_COIN) },
                amount = state.feeCoinAmountText,
                onAmount = { state.feeCoinAmountText = it },
                price = state.feeCoinPriceText,
                onPrice = { state.onFeeCoinPriceText(it) },
                state = d.thirdFee,
                // Gebühr im erhaltenen Coin: ohne Preis, mindert nur die erhaltene Menge
                receivedCoinNote = if (d.feeInReceived) stringResource(R.string.portfolio_tx_swap_fee_received, d.toCoin) else null,
                consumes = d.feeConsumes && d.isSwap,
            )
        }
        if (noteInLedger) TxNoteField(state, 4.dp)
    }
}

/** Summe Menge × Preis (nur mit Preisfeld und beiden Werten). */
@Composable
internal fun TxTotalLine(d: TxFormDerived) {
    val amount = d.amount
    val price = d.price
    if (d.fields.price && amount != null && price != null) {
        Text(
            stringResource(R.string.portfolio_tx_total, PortfolioFormat.usdt((amount * price).toDouble())),
            style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/** Löschen (nur beim Bearbeiten), Abbrechen, Speichern (gesperrt, solange gespeichert wird). */
@Composable
internal fun TxActionsRow(isEdit: Boolean, saving: Boolean, onDelete: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
    ) {
        if (isEdit) {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave, enabled = !saving) { Text(stringResource(R.string.action_save)) }
    }
}
