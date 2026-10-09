@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Kauf oder Verkauf erfassen bzw. bearbeiten (id ≠ 0).
 * Preis in USDT, wird nach der Coin-Wahl mit dem aktuellen Kurs vorbelegt
 * (nur bei neuen Einträgen); leer = Preis unbekannt.
 */
@Composable
fun PortfolioTxSheet(
    initial: TxDraft,
    viewModel: PortfolioViewModel,
    onDismiss: () -> Unit,
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Sperre an: Blatt mit Beständen nicht im Vorschaubild / auf Bildschirmfotos (auch aus der Merkliste)
    val lockEnabled by lockViewModel.lockEnabled.collectAsState()
    val focus = LocalFocusManager.current
    val coins by viewModel.coins.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadCoins() }

    val isEdit = initial.id != 0L
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var coinQuery by rememberSaveable { mutableStateOf(initial.coin) }
    var amountText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.amount)) }
    var priceText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.priceUsdt)) }
    // Vorbelegter Preis darf bei einem Coin-Wechsel ersetzt werden, ein getippter nicht
    var priceAuto by rememberSaveable { mutableStateOf(!isEdit && initial.priceUsdt != null) }
    var pricedCoin by rememberSaveable { mutableStateOf(if (initial.priceUsdt != null || isEdit) initial.coin else "") }
    var time by rememberSaveable { mutableLongStateOf(initial.time) }
    var note by rememberSaveable { mutableStateOf(initial.note.orEmpty()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    val coin = PortfolioCalculator.normalizeCoin(coinQuery)
    val coinValid = coin.length in 1..15 && coin.all { it.isLetterOrDigit() }
    val amount = PriceFormat.parseAmount(amountText)?.takeIf { it > 0.0 }
    val price: Double? = if (priceText.isBlank()) null else PriceFormat.parseAmount(priceText)
    val priceValid = priceText.isBlank() || price != null
    val valid = coinValid && amount != null && priceValid

    // Kurs vorbelegen, sobald ein (anderer) Coin feststeht
    LaunchedEffect(coin, coinValid) {
        if (isEdit || !coinValid || coin == pricedCoin) return@LaunchedEffect
        if (priceText.isNotBlank() && !priceAuto) return@LaunchedEffect
        // Erst nach kurzer Tipp-Pause, nicht bei jedem Buchstaben
        kotlinx.coroutines.delay(400)
        val current = viewModel.currentPrice(coin)
        pricedCoin = coin
        if (priceText.isBlank() || priceAuto) {
            priceText = current?.let { PriceFormat.amountForInput(it) }.orEmpty()
            priceAuto = current != null
        }
    }

    if (pickDate) {
        DateDialog(
            selected = time,
            onSelect = { date -> time = timeFor(date, initial.time); pickDate = false },
            onDismiss = { pickDate = false }
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.portfolio_tx_delete_title)) },
            text = { Text(stringResource(R.string.portfolio_tx_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    viewModel.delete(initial.id)
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(
            securePolicy = if (lockEnabled) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: ändert sich der Inhalt (Laden, Auswahl), springt das Blatt nicht
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            Text(
                stringResource(if (isEdit) R.string.portfolio_edit_tx else R.string.portfolio_add_tx),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            // Kauf | Verkauf
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                PortfolioTxType.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { type = option },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = PortfolioTxType.entries.size),
                        icon = {}
                    ) {
                        Text(stringResource(option.labelRes()), maxLines = 1)
                    }
                }
            }

            // Coin mit Suche
            OutlinedTextField(
                value = coinQuery,
                onValueChange = { coinQuery = it.take(15) },
                label = { Text(stringResource(R.string.portfolio_coin)) },
                placeholder = { Text(stringResource(R.string.portfolio_coin_search)) },
                singleLine = true,
                isError = tried && !coinValid,
                supportingText = if (tried && !coinValid) {
                    { Text(stringResource(R.string.portfolio_coin_invalid)) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            val matches = remember(coins, coin) { matchCoins(coins, coin) }
            if (matches.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(matches, key = { it }) { symbol ->
                        AssistChip(
                            onClick = { coinQuery = symbol; focus.clearFocus() },
                            label = { Text(symbol, maxLines = 1) }
                        )
                    }
                }
            }

            // Anzahl
            val holdings = if (type == PortfolioTxType.SELL && coinValid) viewModel.holdingsOf(coin, initial.id) else null
            val exceeds = holdings != null && amount != null && amount > holdings * (1 + 1e-9) + PortfolioCalculator.EPS
            val amountError = (tried || amountText.isNotBlank()) && amount == null
            val amountMessage: String? = when {
                amountError -> stringResource(R.string.portfolio_invalid)
                exceeds ->
                    stringResource(R.string.portfolio_sell_exceeds, PortfolioFormat.amount(holdings, coin))
                else -> null
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.portfolio_tx_amount)) },
                suffix = if (coinValid) { { Text(coin) } } else null,
                singleLine = true,
                isError = amountError || exceeds,
                supportingText = if (amountMessage != null) { { Text(amountMessage) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            // Preis pro Coin in USDT
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = it; priceAuto = false },
                label = { Text(stringResource(R.string.portfolio_tx_price)) },
                suffix = { Text(PortfolioFormat.USDT) },
                singleLine = true,
                isError = !priceValid,
                supportingText = {
                    Text(
                        stringResource(
                            if (priceValid) R.string.portfolio_tx_price_hint else R.string.portfolio_tx_price_invalid
                        )
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            // Datum (nicht in der Zukunft)
            Surface(
                onClick = { pickDate = true },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
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
                        PortfolioFormat.date(time),
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

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text(stringResource(R.string.portfolio_tx_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )

            // Summe
            if (amount != null && price != null) {
                Text(
                    stringResource(R.string.portfolio_tx_total, PortfolioFormat.usdt(amount * price)),
                    style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
            ) {
                if (isEdit) {
                    TextButton(onClick = { askDelete = true }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    tried = true
                    if (valid) {
                        viewModel.save(
                            TxDraft(
                                id = initial.id,
                                coin = coin,
                                type = type,
                                amount = amount,
                                priceUsdt = price,
                                time = time,
                                note = note.trim().takeIf { it.isNotEmpty() },
                            )
                        )
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

/** Erfassen-Blatt aus der Merkliste: Coin und Kurs vorbelegt. */
@Composable
fun PortfolioQuickAddSheet(
    coin: String,
    priceUsdt: Double?,
    onDismiss: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    PortfolioTxSheet(
        initial = TxDraft(coin = PortfolioCalculator.normalizeCoin(coin), priceUsdt = priceUsdt),
        viewModel = viewModel,
        onDismiss = onDismiss
    )
}

internal fun PortfolioTxType.labelRes(): Int = when (this) {
    PortfolioTxType.BUY -> R.string.portfolio_buy
    PortfolioTxType.SELL -> R.string.portfolio_sell
}

/** Treffer für die Suche: erst «beginnt mit», dann «enthält»; exakter Treffer allein blendet aus. */
private fun matchCoins(coins: List<String>, query: String): List<String> {
    if (query.isEmpty() || coins.isEmpty()) return emptyList()
    val starts = coins.filter { it.startsWith(query) }.sortedWith(compareBy({ it.length }, { it }))
    if (starts.size == 1 && starts[0] == query) return emptyList()
    val contains = coins.filter { !it.startsWith(query) && it.contains(query) }.sortedWith(compareBy({ it.length }, { it }))
    return (starts + contains).take(12)
}

/** Datumswahl (bis heute); auch vom Stichtag-Export verwendet. */
@Composable
internal fun DateDialog(selected: Long, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val todayUtc = utcMidnight(today)
    val state = rememberDatePickerState(
        // DatePickerState wirft, wenn das Startdatum ausserhalb von yearRange liegt
        // (z. B. Zeitstempel aus einer Sicherung) — daher in beide Richtungen begrenzen.
        initialSelectedDateMillis = utcMidnight(localDate(selected))
            .coerceIn(utcMidnight(LocalDate.of(MIN_YEAR, 1, 1)), todayUtc),
        yearRange = MIN_YEAR..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { utc ->
                        onSelect(Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    ) {
        DatePicker(state = state)
    }
}

private const val MIN_YEAR = 2009

private fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Der DatePicker rechnet in UTC-Mitternacht. */
private fun utcMidnight(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Zeitpunkt für ein gewähltes Datum: unverändertes Datum behält die Uhrzeit,
 * heute = jetzt, sonst 12:00 Ortszeit (Reihenfolge am selben Tag nach Erfassung).
 */
private fun timeFor(date: LocalDate, original: Long): Long = when (date) {
    localDate(original) -> original
    LocalDate.now() -> System.currentTimeMillis()
    else -> date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
