// Abschnitte des Transaktions-Blatts (`PortfolioTxSheet`) ab Preis/Wert: Preis bzw. Wert, Schalter je Art, Von/Nach,
// Notiz, Gebühr/Börse, Summe und Knöpfe; die vorderen Abschnitte (Art bis Eingabewährung) stehen in `PortfolioTxSections`.
// Eigene Datei, weil die Abschnitte zusammen zu lang würden; jeder liest [TxFormState] und [TxFormDerived].
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.TextLimit
import java.math.BigDecimal
import java.time.ZoneId
import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid.Field

/** Tausch: Wert des Tauschs (optional, gesamt); sonst Preis pro Coin in USDT bzw. in der Anzeigewährung (Umbuchung, Tausch: keiner). */
@Composable
internal fun TxPriceValueSection(state: TxFormState, d: TxFormDerived, currency: String) {
    val type = state.type
    val entryUnit = if (d.fiatActive) currency else PortfolioFormat.USDT
    val fxDay = state.entryFx?.day
    // Kein plausibler USDT/USD-Kurs: Faktor mit USDT = 1 — gleicher Vermerk wie Summen und Steuer-Export
    val usdtAssumed = if (state.entryFx?.usdtAssumed == true) stringResource(R.string.portfolio_usdt_assumed) else null
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
                        !d.swapValueValid -> stringResource(R.string.portfolio_tx_price_invalid)
                        converted != null -> listOfNotNull(
                            stringResource(R.string.portfolio_tx_fiat_converted, converted.first, converted.second), usdtAssumed,
                        ).joinToString(" · ")
                        else -> stringResource(R.string.portfolio_tx_swap_value_hint)
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).txFocusTarget(LocalTxFocusTargets.current, Field.SWAP_VALUE)
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
                        converted != null -> listOfNotNull(
                            stringResource(R.string.portfolio_tx_fiat_converted, converted.first, converted.second), usdtAssumed,
                        ).joinToString(" · ")
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
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).txFocusTarget(LocalTxFocusTargets.current, Field.PRICE)
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
        onValue = { state.transferFrom = TextLimit.takeGraphemes(it, PortfolioTxForm.MAX_ROUTE) },
        label = stringResource(R.string.portfolio_tx_transfer_from),
        used = usedWallets,
        suggestions = exchanges,
        modifier = Modifier.padding(top = 4.dp),
    )
    RouteField(
        value = state.transferTo,
        onValue = { state.transferTo = TextLimit.takeGraphemes(it, PortfolioTxForm.MAX_ROUTE) },
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
        onValueChange = { state.note = TextLimit.takeGraphemes(it, NOTE_MAX) },
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
            onExchange = { state.exchange = TextLimit.takeGraphemes(it, PortfolioJson.MAX_EXCHANGE) },
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
            stringResource(R.string.portfolio_tx_total, PortfolioFormat.usdt(amount.multiply(price, PortfolioDecimal.MC).toDouble())),
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
