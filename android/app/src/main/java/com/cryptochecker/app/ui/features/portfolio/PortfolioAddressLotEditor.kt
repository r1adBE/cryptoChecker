// Ein Eröffnungsposten einer beobachteten Adresse erfassen/ändern (Build 84). Teil von `PortfolioAddressLotsSheet.kt`.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.PortfolioCostGaps
import com.cryptochecker.app.settings.inputDecimalSeparator
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import java.math.BigDecimal
import java.time.ZoneId

/**
 * Dialog eines Postens: Coin (nur wenn die Adresse mehrere hält), Kaufdatum (bis heute), Menge und Einstand gesamt oder
 * je Coin (in USDT, gespeichert wie eingegeben). Vorschlag: Menge × geprüfter Tagesschluss des Kaufdatums
 * («Vorschlag: Tagesschluss …» mit «Übernehmen»), änderbar. [onDelete] null = neuer Posten.
 */
@Composable
internal fun AddressLotEditor(
    initial: AddressCostLot,
    coins: List<String>,
    viewModel: PortfolioViewModel,
    onSave: (AddressCostLot) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sep = remember { inputDecimalSeparator() }
    var coin by remember { mutableStateOf(initial.coin) }
    var time by remember { mutableLongStateOf(initial.time) }
    var amountText by remember { mutableStateOf(PriceFormat.amountForInput(initial.amount.takeIf { it.signum() > 0 }, sep)) }
    // Gesamt (Standard) oder je Coin — wie gespeichert
    var perCoin by remember { mutableStateOf(initial.priceUsdt != null) }
    var valueText by remember { mutableStateOf(PriceFormat.amountForInput(initial.priceUsdt ?: initial.costUsdt, sep)) }
    var pickDate by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    val amount = PriceFormat.parseDecimalAmount(amountText, sep)?.takeIf { it.signum() > 0 }
    val value = PriceFormat.parseDecimalAmount(valueText, sep)?.takeIf { it.signum() >= 0 }
    val lot = if (amount != null && value != null) {
        AddressCostLot(initial.id, coin, time, amount, priceUsdt = value.takeIf { perCoin }, costUsdt = value.takeIf { !perCoin })
    } else null
    // Vorschlag: geprüfter Tagesschluss des Kaufdatums (gleiche Quelle wie das Erfassen-Blatt)
    var close by remember(coin, time) { mutableStateOf<BigDecimal?>(null) }
    LaunchedEffect(coin, time) { close = viewModel.historicClose(coin, localDate(time)) }

    if (pickDate) {
        DateDialog(selected = time, onSelect = { d ->
            time = d.atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli()
            pickDate = false
        }, onDismiss = { pickDate = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (onDelete == null) R.string.address_lots_new else R.string.address_lots_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (coins.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        coins.forEach { c -> FilterChip(selected = c == coin, onClick = { coin = c }, label = { Text(c) }) }
                    }
                }
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.label_value, stringResource(R.string.address_lots_date), PortfolioFormat.date(time)))
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.address_lots_amount) + " ($coin)") },
                    singleLine = true,
                    isError = tried && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(selected = !perCoin, onClick = { perCoin = false }, label = { Text(stringResource(R.string.address_lots_mode_total)) })
                    FilterChip(selected = perCoin, onClick = { perCoin = true }, label = { Text(stringResource(R.string.address_lots_mode_price)) })
                }
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text(stringResource(if (perCoin) R.string.address_lots_price else R.string.address_lots_total)) },
                    singleLine = true,
                    isError = tried && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                close?.let { c ->
                    val suggested = if (perCoin) c else amount?.let { AddressCostLots.suggestion(it, c) }
                    if (suggested != null) {
                        Text(
                            stringResource(R.string.cost_gap_suggestion, PortfolioFormat.date(time), maskAmount(PortfolioFormat.usdt(suggested.toDouble()))),
                            style = MaterialTheme.typography.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { valueText = PriceFormat.amountForInput(suggested, sep) }) {
                            Text(stringResource(R.string.cost_gap_apply))
                        }
                    }
                }
                if (tried && lot == null) {
                    Text(stringResource(R.string.address_lots_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.body)
                }
                // Je Coin aus einem Gesamtbetrag (nur Anzeige): «≈ 30'000 USDT je Coin»
                if (!perCoin && amount != null && value != null) {
                    PortfolioCostGaps.priceFromTotal(value, amount)?.let {
                        Text(
                            stringResource(R.string.label_value, stringResource(R.string.address_lots_price), PortfolioFormat.price(it.toDouble())),
                            style = MaterialTheme.typography.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                lot?.let(onSave)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
