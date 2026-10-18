@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.settings.inputDecimalSeparator
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Transaktion erfassen bzw. bearbeiten (id ≠ 0): Kauf, Verkauf, Umbuchung, Belohnung, Ein-/Auszahlung —
 * Felder und Prüfung je Art nach [PortfolioTxForm.fields] (Regeln in PortfolioLedger).
 * Preis in USDT, wird nach der Coin-Wahl mit dem aktuellen Kurs vorbelegt
 * (nur bei neuen Einträgen von heute, [PortfolioTxForm]); leer = Preis unbekannt. Belohnung/Einzahlung an
 * einem vergangenen Tag: geprüfter Tagesschluss vorbelegt (markiert).
 */
@Composable
fun PortfolioTxSheet(
    initial: TxDraft,
    viewModel: PortfolioViewModel,
    onDismiss: () -> Unit,
    lockViewModel: PortfolioLockViewModel = hiltViewModel(),
    /** Erste Transaktion mit ausgeschaltetem Portfolio gespeichert: Rückfrage (nur aus der Merkliste bzw. dem Markt). */
    onAskEnablePortfolio: () -> Unit = {},
) {
    // Ungespeicherte Änderungen: Wischen / Tippen daneben / Zurück fragen erst nach (Verwerfen | Weiter bearbeiten)
    var askDiscard by remember { mutableStateOf(false) }
    // Sperre an: Blatt mit Beständen nicht im Vorschaubild / auf Bildschirmfotos (auch aus der Merkliste)
    val lockEnabled by lockViewModel.lockEnabled.collectAsState()
    val focus = LocalFocusManager.current
    val coins by viewModel.coins.collectAsState()
    val exchanges by viewModel.exchanges.collectAsState()
    // Build 46: selbst benutzte Börsen/Wallets (Vorschläge und «Meintest du …?» bei Von/Nach)
    val savedTxs by viewModel.transactions.collectAsState()
    val usedWallets = remember(savedTxs) {
        com.cryptochecker.app.domain.portfolio.PortfolioWalletNames.known(
            savedTxs.orEmpty().flatMap { tx -> listOf(tx.exchange, tx.transferFrom, tx.transferTo) }
        )
    }
    LaunchedEffect(Unit) {
        viewModel.loadCoins()
        viewModel.loadExchanges()
    }

    val isEdit = initial.id != 0L
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var coinQuery by rememberSaveable { mutableStateOf(initial.coin) }
    // Dezimalzeichen der Region (Sprache der App, Region des Geräts) — wie bei den Alarmen
    val decimalSeparator = remember { inputDecimalSeparator() }
    var amountText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.amount, decimalSeparator)) }
    var priceText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.priceUsdt, decimalSeparator)) }
    // Kurs, an dem ein mehrdeutiger Preis («60.000») gemessen wird: gespeicherter bzw. aktueller Kurs
    var priceHint by rememberSaveable { mutableStateOf(initial.priceUsdt?.takeIf { it.signum() > 0 }?.toDouble()) }
    // Vorbelegter Preis darf bei einem Coin-Wechsel ersetzt werden, ein getippter nicht
    var priceAuto by rememberSaveable { mutableStateOf(!isEdit && initial.priceUsdt != null) }
    // Preis = geprüfter Tagesschluss eines vergangenen Tags (Belohnung/Einzahlung), markiert; Tag als Text
    var priceHistoricDay by rememberSaveable { mutableStateOf<String?>(null) }
    var pricedCoin by rememberSaveable { mutableStateOf(if (initial.priceUsdt != null || isEdit) initial.coin else "") }
    var time by rememberSaveable { mutableLongStateOf(initial.time) }
    // Einzahlung: ursprüngliches Kaufdatum (optional, ≤ Datum der Einzahlung) für die Haltedauer im Steuer-Export
    var acquiredTime by rememberSaveable { mutableStateOf(initial.acquiredTime) }
    var pickAcquired by remember { mutableStateOf(false) }
    // Höchstens 120 Zeichen wie die Notiz in der Merkliste (eine längere alte Notiz wird beim Speichern gekürzt)
    var note by rememberSaveable { mutableStateOf(initial.note.orEmpty().take(NOTE_MAX)) }
    // Gebühr und Börse (D1): zugeklappt, ausser beim Bearbeiten mit Angaben
    var feeText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.fee, decimalSeparator)) }
    var feeUnit by rememberSaveable { mutableStateOf(initial.feeUnit) }
    var exchange by rememberSaveable { mutableStateOf(initial.exchange.orEmpty()) }
    // Umbuchung: von / nach (freier Text, Vorschläge aus den Börsen)
    var transferFrom by rememberSaveable { mutableStateOf(initial.transferFrom.orEmpty()) }
    var transferTo by rememberSaveable { mutableStateOf(initial.transferTo.orEmpty()) }
    // Schenkung an Ehe-/Lebenspartner (eigenes Feld, nur bei einer Schenkung)
    val initialSpouse = initial.giftToSpouse && initial.type == PortfolioTxType.GIFT
    var giftToSpouse by rememberSaveable { mutableStateOf(initialSpouse) }
    // Belohnung als Zins (Lending; AT: Einkunft beim Zufluss) — eigenes Feld, nur bei einer Belohnung
    val initialInterest = initial.interest && initial.type == PortfolioTxType.REWARD
    var interest by rememberSaveable { mutableStateOf(initialInterest) }
    // Gebühr in einem dritten Coin
    var feeCoinText by rememberSaveable { mutableStateOf(initial.feeCoin.orEmpty()) }
    var feeCoinAmountText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.feeCoinAmount, decimalSeparator)) }
    var feeCoinPriceText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.feeCoinPriceUsdt, decimalSeparator)) }
    var feeCoinPriceAuto by rememberSaveable { mutableStateOf(false) }
    // Tausch: erhaltener Coin und Menge, Wert des Tauschs (optional, gesamt)
    var toCoinQuery by rememberSaveable { mutableStateOf(initial.toCoin.orEmpty()) }
    var toAmountText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.toAmount, decimalSeparator)) }
    var swapValueText by rememberSaveable { mutableStateOf(PriceFormat.amountForInput(initial.swapValueUsdt, decimalSeparator)) }
    // K-2: Preis/Wert in der Anzeigewährung eingeben (gespeichert wird immer USDT, umgerechnet mit dem Tageskurs)
    val currency by viewModel.currency.collectAsState()
    var entryFiat by rememberSaveable { mutableStateOf(false) }
    // Umschalten auf die Anzeigewährung: Felder (noch USDT) umrechnen, sobald der Kurs da ist
    var pendingToFiat by rememberSaveable { mutableStateOf(false) }
    var entryFx by remember { mutableStateOf<Pair<BigDecimal, LocalDate>?>(null) }
    var entryFxKey by remember { mutableStateOf<String?>(null) }
    var entryFxState by remember { mutableStateOf<EntryFxState?>(null) }
    // Kauf: auch die Notiz steht im zugeklappten Abschnitt «Gebühr, Börse, Notiz»
    var showLedger by rememberSaveable {
        mutableStateOf(
            initial.fee != null || !initial.exchange.isNullOrBlank() || initial.feeCoin != null ||
                (initial.type == PortfolioTxType.BUY && !initial.note.isNullOrBlank())
        )
    }
    var typeMenu by remember { mutableStateOf(false) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    val fields = PortfolioTxForm.fields(type)
    val coin = PortfolioCalculator.normalizeCoin(coinQuery)
    val coinValid = coin.length in 1..15 && coin.all { it.isLetterOrDigit() }
    // Menge ohne Kurs-Hinweis (mehrdeutig → Dezimalzeichen der Region), Preis am Kurs gemessen.
    // Exakt gelesen (BigDecimal): 0.00000001 BTC = 1 Satoshi, ohne Gleitkomma-Rundung
    val amount = PriceFormat.parseDecimalAmount(amountText, decimalSeparator)?.takeIf { it.signum() > 0 }
    val isSwap = type == PortfolioTxType.SWAP
    // Eingabe in der Anzeigewährung: nur mit Preis-/Wertfeld und nicht für USD (USDT gilt als USD)
    val day = localDate(time)
    val fiatPossible = currency != "USD" && (fields.price || isSwap)
    val fxRate = entryFx?.first?.takeIf { entryFxState == EntryFxState.READY && entryFxKey == "$currency|$day" }
    val fiatActive = entryFiat && fiatPossible && !pendingToFiat
    val priceHintEntry = if (fiatActive) fxRate?.let { r -> priceHint?.times(r.toDouble()) } else priceHint
    val priceEntered: BigDecimal? = if (priceText.isBlank()) null else PriceFormat.parseDecimalAmount(priceText, decimalSeparator, priceHintEntry)
    // Gespeichert wird USDT: Eingabe in der Anzeigewährung ÷ Tageskurs
    val price: BigDecimal? = if (fiatActive) PortfolioTxForm.fiatToUsdt(priceEntered, fxRate) else priceEntered
    // Preis 0 ausdrücklich eingegeben: nur bei einer importierten Transaktion abgelehnt (Kennzeichen der Gebühr-Zeile);
    // von Hand erfasst ist 0 ein echter Preis (z. B. wertlosen Coin verkaufen)
    val priceZero = fields.price && PortfolioTxForm.priceZero(priceEntered, imported = !initial.importKey.isNullOrBlank())
    val priceValid = !fields.price || priceText.isBlank() || (price != null && !priceZero)
    // Tausch: erhaltener Coin ≠ abgegebener, Menge > 0; Wert optional (≥ 0)
    val toCoin = PortfolioCalculator.normalizeCoin(toCoinQuery)
    val toCoinValid = !isSwap || PortfolioTxForm.swapToCoinValid(toCoinQuery, coin)
    val toAmount = PriceFormat.parseDecimalAmount(toAmountText, decimalSeparator)?.takeIf { it.signum() > 0 }
    val toAmountValid = !isSwap || toAmount != null
    val swapValueEntered = if (swapValueText.isBlank()) null else PriceFormat.parseDecimalAmount(swapValueText, decimalSeparator)
    val swapValue = if (fiatActive) PortfolioTxForm.fiatToUsdt(swapValueEntered, fxRate) else swapValueEntered
    val swapValueValid = !isSwap || swapValueText.isBlank() || (swapValue != null && swapValue.signum() >= 0)
    // Gebühr: leer = keine; 0 gilt als keine (Belohnung/Einzahlung haben keine)
    val hasFee = fields.feeWithUnit || fields.coinFeeOnly
    val fee: BigDecimal? = if (!hasFee || feeText.isBlank()) null else PriceFormat.parseDecimalAmount(feeText, decimalSeparator)
    val feeValid = !hasFee || feeText.isBlank() || fee != null
    val effectiveFeeUnit = PortfolioTxForm.feeUnitFor(type, feeUnit)
    // Gespeicherte Gebühr, die das Blatt für diese Art nicht zeigen kann (z. B. CSV-Import: Auszahlung mit
    // USDT-Gebühr, Einzahlung mit Gebühr): beim Bearbeiten ohne Änderung von Art und Gebühr unverändert
    // behalten — sonst würde sie still gelöscht oder als Gebühr im Coin umgedeutet
    val keepStoredFee = isEdit && type == initial.type && initial.fee != null &&
        feeText == PriceFormat.amountForInput(initial.fee, decimalSeparator) &&
        !(fields.feeWithUnit || (fields.coinFeeOnly && initial.feeUnit == PortfolioFeeUnit.COIN))
    val feeCoinAmount = PriceFormat.parseDecimalAmount(feeCoinAmountText, decimalSeparator)
    val feeCoinPrice = if (feeCoinPriceText.isBlank()) null else PriceFormat.parseDecimalAmount(feeCoinPriceText, decimalSeparator)
    // Tausch: Gebühr im erhaltenen Coin braucht keinen Preis (mindert nur die erhaltene Menge)
    val feeInReceived = PortfolioTxForm.feeInReceivedCoin(type, feeCoinText, toCoinQuery)
    val thirdFee = if (!fields.thirdCoinFee) PortfolioTxForm.ThirdFee.NONE else PortfolioTxForm.thirdFee(
        feeCoinText, coin, feeCoinAmount, feeCoinAmountText.isBlank(), feeCoinPrice, feeCoinPriceText.isBlank(),
    ).let { if (feeInReceived && it == PortfolioTxForm.ThirdFee.INCOMPLETE) PortfolioTxForm.ThirdFee.COMPLETE else it }
    // Gebühr verbraucht die ganze erhaltene Menge: nicht speicherbar (Kauf: Gebühr im Coin, Tausch: im erhaltenen Coin)
    val feeConsumes = PortfolioTxForm.feeConsumesReceived(
        type, amount, fee, effectiveFeeUnit, toAmount, if (feeInReceived) feeCoinAmount else null,
    )
    val valid = coinValid && amount != null && priceValid && feeValid && thirdFee != PortfolioTxForm.ThirdFee.INVALID &&
        toCoinValid && toAmountValid && swapValueValid && !feeConsumes
    // Der vorbelegte Kurs ist der heutige — gilt nur für eine Transaktion von heute (Ortszeit)
    val isToday = localDate(time) == LocalDate.now()
    // Vom Nutzer geändert? Vorbelegte Preise (heutiger Kurs, Tagesschluss) zählen nicht als Änderung
    val dirty = type != initial.type || coinQuery != initial.coin ||
        amountText != PriceFormat.amountForInput(initial.amount, decimalSeparator) ||
        (!priceAuto && priceHistoricDay == null && priceText != PriceFormat.amountForInput(initial.priceUsdt, decimalSeparator)) ||
        time != initial.time || acquiredTime != initial.acquiredTime || note != initial.note.orEmpty().take(NOTE_MAX) ||
        feeText != PriceFormat.amountForInput(initial.fee, decimalSeparator) || feeUnit != initial.feeUnit ||
        exchange != initial.exchange.orEmpty() || transferFrom != initial.transferFrom.orEmpty() ||
        transferTo != initial.transferTo.orEmpty() || giftToSpouse != initialSpouse || interest != initialInterest ||
        feeCoinText != initial.feeCoin.orEmpty() ||
        feeCoinAmountText != PriceFormat.amountForInput(initial.feeCoinAmount, decimalSeparator) ||
        (!feeCoinPriceAuto && feeCoinPriceText != PriceFormat.amountForInput(initial.feeCoinPriceUsdt, decimalSeparator)) ||
        toCoinQuery != initial.toCoin.orEmpty() ||
        toAmountText != PriceFormat.amountForInput(initial.toAmount, decimalSeparator) ||
        swapValueText != PriceFormat.amountForInput(initial.swapValueUsdt, decimalSeparator)
    val dirtyNow by rememberUpdatedState(dirty)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow) { askDiscard = true; false } else true
        },
    )
    // Abbrechen bzw. Schliessen: mit Änderungen erst nachfragen
    val requestClose: () -> Unit = { if (dirty) { askDiscard = true } else { onDismiss() } }
    // Speichern läuft (kein Doppeltipp); Fehler beim Speichern als Meldung im Blatt (über dem Blatt sichtbar)
    var saving by remember { mutableStateOf(false) }
    val saveErrorHost = remember { SnackbarHostState() }
    val saveFailedText = stringResource(R.string.portfolio_tx_save_failed)
    val scope = rememberCoroutineScope()

    // Kurs vorbelegen, sobald ein (anderer) Coin feststeht; ein getippter Preis bleibt,
    // der Kurs dient dann nur als Hinweis für eine mehrdeutige Eingabe. Nicht für einen anderen Tag.
    LaunchedEffect(coin, coinValid) {
        if (isEdit || !coinValid || coin == pricedCoin) return@LaunchedEffect
        // Erst nach kurzer Tipp-Pause, nicht bei jedem Buchstaben
        kotlinx.coroutines.delay(400)
        val current = viewModel.currentPrice(coin)?.takeIf { it.isFinite() && it > 0.0 }
        pricedCoin = coin
        priceHint = current
        val today = localDate(time) == LocalDate.now()
        if (!PortfolioTxForm.mayPrefill(isEdit, today)) {
            // Anderer Tag: kein heutiger Kurs im Feld (ein noch vorbelegter wird geleert)
            if (priceAuto) { priceText = ""; priceAuto = false }
        } else if (!entryFiat && (priceText.isBlank() || priceAuto)) {
            priceText = current?.let { PriceFormat.amountForInput(PortfolioDecimal.of(it), decimalSeparator) }.orEmpty()
            priceAuto = current != null
        }
    }

    // Belohnung/Einzahlung an einem vergangenen Tag: geprüften Tagesschluss vorbelegen (markiert)
    LaunchedEffect(type, coin, coinValid, time) {
        // Ein vorbelegter Tagesschluss (anderer Coin) wird ersetzt, ein getippter Preis bleibt
        val replaceable = priceText.isBlank() || priceHistoricDay != null
        // Eingabe in der Anzeigewährung: kein USDT-Tagesschluss ins Feld
        if (entryFiat || !coinValid || !PortfolioTxForm.mayPrefillHistoricClose(type, isEdit, localDate(time) == LocalDate.now(), replaceable)) {
            return@LaunchedEffect
        }
        if (priceHistoricDay != null) { priceText = ""; priceHistoricDay = null }
        kotlinx.coroutines.delay(400)
        val day = localDate(time)
        val close = viewModel.historicClose(coin, day) ?: return@LaunchedEffect
        if (priceText.isBlank()) {
            priceText = PriceFormat.amountForInput(close, decimalSeparator)
            priceHint = close.toDouble()
            priceAuto = false
            priceHistoricDay = PortfolioFormat.date(time)
        }
    }

    // Gebühr-Coin: Preis mit dem aktuellen Kurs vorbelegen — nur bei einer Transaktion von heute
    val feeCoinSymbol = PortfolioCalculator.normalizeCoin(feeCoinText)
    LaunchedEffect(feeCoinSymbol) {
        val validFeeCoin = feeCoinSymbol.length in 1..15 && feeCoinSymbol.all { it.isLetterOrDigit() } && feeCoinSymbol != coin
        if (!validFeeCoin) return@LaunchedEffect
        if (feeCoinPriceAuto) { feeCoinPriceText = ""; feeCoinPriceAuto = false }
        if (!PortfolioTxForm.mayPrefillFeeCoinPrice(isEdit, localDate(time) == LocalDate.now(), feeCoinPriceText.isBlank())) {
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        val current = viewModel.currentPrice(feeCoinSymbol)?.takeIf { it.isFinite() && it > 0.0 } ?: return@LaunchedEffect
        if (feeCoinPriceText.isBlank()) {
            feeCoinPriceText = PriceFormat.amountForInput(PortfolioDecimal.of(current), decimalSeparator)
            feeCoinPriceAuto = true
        }
    }

    // K-2: Tageskurs USD → Anzeigewährung für das Datum der Transaktion (heute: aktueller Kurs), nur in der Anzeigewährung
    LaunchedEffect(currency, day, entryFiat) {
        if (!entryFiat || currency == "USD") return@LaunchedEffect
        val key = "$currency|$day"
        if (entryFxKey != key || entryFxState == EntryFxState.LOADING || entryFxState == null) {
            entryFxKey = key
            entryFxState = EntryFxState.LOADING
            entryFx = null
            val loaded = viewModel.entryFx(currency, day)
            entryFx = loaded
            entryFxState = if (loaded != null) EntryFxState.READY else EntryFxState.FAILED
        }
        val rate = entryFx?.first
        if (pendingToFiat) {
            pendingToFiat = false
            if (rate == null) {
                // Kein Kurs: bei USDT bleiben (Felder unverändert)
                entryFiat = false
            } else {
                priceText = convertEntry(priceText, decimalSeparator, priceHint) { PortfolioTxForm.usdtToFiat(it, rate) }
                swapValueText = convertEntry(swapValueText, decimalSeparator, null) { PortfolioTxForm.usdtToFiat(it, rate) }
                priceAuto = false
                priceHistoricDay = null
            }
        }
    }

    if (pickDate) {
        DateDialog(
            selected = time,
            onSelect = { date ->
                time = timeFor(date, initial.time)
                pickDate = false
                // Ursprüngliches Kaufdatum höchstens das Datum der Einzahlung
                acquiredTime = acquiredTime?.coerceAtMost(time)
                // Tagesschluss gehört zum alten Tag: leeren (der neue wird geladen)
                if (priceHistoricDay != null) { priceText = ""; priceHistoricDay = null }
                // Heutiger Kurs des Gebühr-Coins gilt nur für heute
                if (feeCoinPriceAuto && date != LocalDate.now()) { feeCoinPriceText = ""; feeCoinPriceAuto = false }
                // Vorbelegter heutiger Kurs gilt nur für heute: anderer Tag → leeren, zurück auf heute → wieder vorbelegen
                val action = PortfolioTxForm.onDateChange(
                    isEdit = isEdit,
                    isToday = date == LocalDate.now(),
                    priceAuto = priceAuto,
                    priceBlank = priceText.isBlank(),
                )
                when (action) {
                    PortfolioTxForm.PriceAction.CLEAR -> { priceText = ""; priceAuto = false }
                    PortfolioTxForm.PriceAction.PREFILL -> {
                        val current = priceHint?.takeIf { !entryFiat && coinValid && coin == pricedCoin && it.isFinite() && it > 0.0 }
                        if (current != null) {
                            priceText = PriceFormat.amountForInput(PortfolioDecimal.of(current), decimalSeparator)
                            priceAuto = true
                        }
                    }
                    PortfolioTxForm.PriceAction.KEEP -> Unit
                }
            },
            onDismiss = { pickDate = false }
        )
    }

    if (pickAcquired) {
        DateDialog(
            selected = acquiredTime ?: time,
            maxDate = localDate(time),
            onSelect = { date ->
                // Unverändertes Datum behält die Uhrzeit, sonst 12:00 Ortszeit; nie nach der Einzahlung
                val original = acquiredTime ?: initial.acquiredTime
                val picked = if (original != null && localDate(original) == date) original
                else date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                acquiredTime = picked.coerceAtMost(time)
                pickAcquired = false
            },
            onDismiss = { pickAcquired = false }
        )
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.portfolio_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    onDismiss()
                }) { Text(stringResource(R.string.portfolio_discard_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.portfolio_discard_keep)) }
            }
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
        // Zurück mit Änderungen: nachfragen statt still verwerfen
        BackHandler(enabled = dirty) { askDiscard = true }
        // Box: Meldung «nicht gespeichert» unten über dem Inhalt des Blatts
        Box {
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

            // Build 46: Art als eine kompakte Wahl «Art: Kauf ▾» (die übrigen Arten im Menü) statt sieben Chips
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
                            onClick = { type = option; typeMenu = false },
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

            // Coin mit Suche
            OutlinedTextField(
                value = coinQuery,
                onValueChange = { coinQuery = it.take(15) },
                label = { Text(stringResource(if (isSwap) R.string.portfolio_tx_swap_given_coin else R.string.portfolio_coin)) },
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

            // Datum (nicht in der Zukunft) — vor Menge und Preis: ein Datumswechsel kann den Preis leeren,
            // das soll nicht unbemerkt über dem sichtbaren Bereich geschehen
            Surface(
                onClick = { pickDate = true },
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

            // Einzahlung: ursprüngliches Kaufdatum (optional) — Haltedauer im Steuer-Export
            if (type == PortfolioTxType.DEPOSIT) {
                AcquiredDateField(
                    acquiredTime = acquiredTime,
                    onPick = { pickAcquired = true },
                    onClear = { acquiredTime = null },
                )
            }

            // Menge
            val holdings = if (fields.outflow && coinValid) viewModel.holdingsOf(coin, initial.id) else null
            // Abgang (Verkauf, Auszahlung, Umbuchung) + Gebühr im Coin über dem Bestand: Warnung (Speichern bleibt möglich)
            val exceeds = PortfolioTxForm.outflowExceeds(type, amount, fee, feeUnit, holdings)
            // FIFO je Wallet: auch der Bestand DIESER Wallet (Börse bzw. «Von» der Umbuchung) muss reichen
            val walletName = (if (type == PortfolioTxType.TRANSFER) transferFrom else exchange).trim()
            val walletHoldings = if (fields.outflow && coinValid && !exceeds) viewModel.walletHoldingsOf(coin, walletName, initial.id) else null
            val walletExceeds = walletHoldings != null && PortfolioTxForm.outflowExceeds(type, amount, fee, feeUnit, walletHoldings)
            val amountError = (tried || amountText.isNotBlank()) && amount == null
            val amountMessage: String? = when {
                amountError -> stringResource(R.string.portfolio_invalid)
                walletExceeds -> stringResource(
                    R.string.portfolio_sell_exceeds_wallet,
                    PortfolioFormat.amount(walletHoldings, coin),
                    walletName.ifEmpty { stringResource(R.string.tax_export_wallet_unspecified) },
                )
                exceeds && holdings != null -> stringResource(
                    if (PortfolioTxForm.sellExceedsIncludesFee(fee, effectiveFeeUnit)) {
                        R.string.portfolio_sell_exceeds_fee
                    } else {
                        R.string.portfolio_sell_exceeds
                    },
                    PortfolioFormat.amount(holdings, coin),
                )
                else -> null
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(if (isSwap) R.string.portfolio_tx_swap_given_amount else R.string.portfolio_tx_amount)) },
                suffix = if (coinValid) { { Text(coin) } } else null,
                singleLine = true,
                isError = amountError || exceeds || walletExceeds,
                supportingText = if (amountMessage != null) { { Text(amountMessage) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            // Tausch: erhaltener Coin und Menge
            if (isSwap) {
                SwapReceivedFields(
                    toCoin = toCoinQuery,
                    onToCoin = { toCoinQuery = it.take(15) },
                    toCoinError = when {
                        !(tried || toCoinQuery.isNotBlank()) || toCoinValid -> null
                        coinValid && toCoin == coin -> stringResource(R.string.portfolio_tx_swap_same_coin)
                        else -> stringResource(R.string.portfolio_coin_invalid)
                    },
                    coins = coins,
                    toAmount = toAmountText,
                    onToAmount = { toAmountText = it },
                    toAmountError = (tried || toAmountText.isNotBlank()) && toAmount == null,
                )
            }

            // K-2: Preis/Wert in USDT oder in der Anzeigewährung (umgerechnet mit dem Tageskurs, gespeichert in USDT)
            if (fiatPossible) {
                EntryCurrencyRow(
                    currency = currency,
                    fiat = entryFiat,
                    fiatEnabled = !(entryFxState == EntryFxState.FAILED && entryFxKey == "$currency|$day"),
                    onUsdt = {
                        if (entryFiat) {
                            if (fiatActive) {
                                // Zurück in USDT: Felder umrechnen (ohne Kurs leeren — sonst stünde ein Fiat-Betrag als USDT da)
                                priceText = price?.let { PriceFormat.amountForInput(roundForInput(it), decimalSeparator) }.orEmpty()
                                swapValueText = swapValue?.let { PriceFormat.amountForInput(roundForInput(it), decimalSeparator) }.orEmpty()
                                priceAuto = false
                                priceHistoricDay = null
                            }
                            entryFiat = false
                            pendingToFiat = false
                        }
                    },
                    onFiat = { if (!entryFiat) { entryFiat = true; pendingToFiat = true } },
                )
                val fxFailed = entryFxState == EntryFxState.FAILED && entryFxKey == "$currency|$day"
                val fxNote = when {
                    // Kein Kurs für diesen Tag: Anzeigewährung gesperrt, mit Hinweis
                    fxFailed -> stringResource(R.string.portfolio_tx_fiat_no_fx)
                    !entryFiat -> null
                    pendingToFiat || fxRate == null -> stringResource(R.string.portfolio_tx_fiat_loading)
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
            val entryUnit = if (fiatActive) currency else PortfolioFormat.USDT
            val fxDay = entryFx?.second
            // «≈ 12’345.67 USDT zum Kurs vom 03.03.2025» unter einem Feld in der Anzeigewährung: Betrag und Kursdatum
            val convertedLine: (BigDecimal?, Boolean) -> Pair<String, String>? = { usdt, perCoin ->
                if (fiatActive && usdt != null && fxDay != null) {
                    (if (perCoin) PortfolioFormat.price(usdt.toDouble()) else PortfolioFormat.usdt(usdt.toDouble())) to
                        PortfolioFormat.date(fxDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                } else null
            }

            // Tausch: Wert des Tauschs (optional, gesamt)
            if (isSwap) {
                val converted = convertedLine(swapValue, false)
                OutlinedTextField(
                    value = swapValueText,
                    onValueChange = { swapValueText = it },
                    label = { Text(stringResource(R.string.portfolio_tx_swap_value)) },
                    suffix = { Text(entryUnit) },
                    singleLine = true,
                    isError = !swapValueValid,
                    supportingText = {
                        Text(
                            when {
                                !swapValueValid -> stringResource(R.string.portfolio_invalid)
                                converted != null -> stringResource(R.string.portfolio_tx_fiat_converted, converted.first, converted.second)
                                else -> stringResource(R.string.portfolio_tx_swap_value_hint)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            // Preis pro Coin in USDT bzw. in der Anzeigewährung (Umbuchung, Tausch: keiner)
            if (fields.price) {
                val receipt = type == PortfolioTxType.REWARD || type == PortfolioTxType.DEPOSIT
                // Verschenkt, Damit bezahlt: Marktwert je Coin (Damit bezahlt: Erlös = Menge × Wert, kein Geld erhalten)
                val gift = type == PortfolioTxType.GIFT || type == PortfolioTxType.PAYMENT
                val historicDay = priceHistoricDay
                val converted = convertedLine(price, true)
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it; priceAuto = false; priceHistoricDay = null },
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
                    isError = !priceValid,
                    supportingText = {
                        Text(
                            when {
                                // Anzeigewährung ohne (geladenen) Kurs: Hinweis steht schon über dem Feld
                                priceZero -> stringResource(R.string.portfolio_tx_price_zero)
                                !priceValid && fiatActive && fxRate == null -> stringResource(R.string.portfolio_tx_fiat_no_fx)
                                !priceValid -> stringResource(R.string.portfolio_tx_price_invalid)
                                converted != null -> stringResource(R.string.portfolio_tx_fiat_converted, converted.first, converted.second)
                                // Geprüfter Tagesschluss vorbelegt: so markiert
                                historicDay != null -> stringResource(R.string.portfolio_tx_price_historic, historicDay)
                                receipt -> stringResource(R.string.portfolio_tx_price_receipt_hint)
                                // Ohne Marktwert: zum Tagesschluss geschätzt (PortfolioLedger.valueAtDayClose)
                                gift -> stringResource(R.string.portfolio_tx_gift_price_hint)
                                // Wert nur für die Verlaufsrechnung (PortfolioLedger.flow); der Abgang zählt immer zum Einstand
                                type == PortfolioTxType.WITHDRAWAL -> stringResource(R.string.portfolio_tx_withdrawal_price_hint)
                                // Anderer Tag: der heutige Kurs passt nicht — Kurs vom Kauftag eintragen
                                PortfolioTxForm.showPastPriceHint(isEdit, isToday) -> stringResource(R.string.portfolio_tx_price_past_hint)
                                else -> stringResource(R.string.portfolio_tx_price_hint)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            // Verschenkt an Ehe-/Lebenspartner: UK/CA ohne Gewinn und Verlust (TaxTemplate.spouseGiftNeutral)
            if (type == PortfolioTxType.GIFT) {
                SwitchRow(
                    title = stringResource(R.string.portfolio_tx_gift_spouse),
                    checked = giftToSpouse,
                    onCheckedChange = { giftToSpouse = it },
                    subtitle = stringResource(R.string.portfolio_tx_gift_spouse_hint),
                )
            }

            // Belohnung als Zins (Lending): AT zählt ihn beim Zufluss (Anschaffungskosten = Wert), Staking nicht
            if (type == PortfolioTxType.REWARD) {
                SwitchRow(
                    title = stringResource(R.string.portfolio_tx_interest),
                    checked = interest,
                    onCheckedChange = { interest = it },
                    subtitle = stringResource(R.string.portfolio_tx_interest_hint),
                )
            }

            // Umbuchung: von / nach
            if (fields.route) {
                RouteField(
                    value = transferFrom,
                    onValue = { transferFrom = it.take(PortfolioTxForm.MAX_ROUTE) },
                    label = stringResource(R.string.portfolio_tx_transfer_from),
                    used = usedWallets,
                    suggestions = exchanges,
                    modifier = Modifier.padding(top = 4.dp),
                )
                RouteField(
                    value = transferTo,
                    onValue = { transferTo = it.take(PortfolioTxForm.MAX_ROUTE) },
                    label = stringResource(R.string.portfolio_tx_transfer_to),
                    used = usedWallets,
                    suggestions = exchanges,
                    hint = stringResource(R.string.portfolio_tx_transfer_to_hint),
                )
            }

            // Kauf (Build 46): nur Coin, Datum, Menge, Preis offen — die Notiz steht im zugeklappten Abschnitt
            val noteInLedger = type == PortfolioTxType.BUY
            val noteField = @Composable { top: Dp ->
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(NOTE_MAX) },
                    label = { Text(stringResource(R.string.portfolio_tx_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = top)
                )
            }
            if (!noteInLedger) noteField(12.dp)

            // Gebühren und Börse (optional, zugeklappt); Belohnung/Einzahlung: nur die Börse
            ExpandToggleButton(
                text = stringResource(
                    when {
                        noteInLedger -> R.string.portfolio_tx_more_buy
                        fields.feeWithUnit || (fields.coinFeeOnly && fields.exchange) -> R.string.ledger_more_toggle
                        hasFee -> R.string.portfolio_tx_fees_toggle
                        else -> R.string.ledger_exchange
                    }
                ),
                expanded = showLedger,
                onClick = { showLedger = !showLedger },
                modifier = Modifier.padding(top = 4.dp)
            )
            if (showLedger) {
                LedgerFields(
                    fields = fields,
                    type = type,
                    feeText = feeText,
                    onFeeText = { feeText = it },
                    feeValid = feeValid,
                    feeConsumes = feeConsumes && !isSwap,
                    feeUnit = feeUnit,
                    onFeeUnit = { feeUnit = it },
                    coin = if (coinValid) coin else "",
                    exchange = exchange,
                    exchanges = exchanges,
                    onExchange = { exchange = it.take(PortfolioJson.MAX_EXCHANGE) },
                )
                // Gebühr in einem dritten Coin: direkt im Abschnitt (keine zweite Aufklapp-Ebene)
                if (fields.thirdCoinFee) {
                    Text(
                        stringResource(R.string.portfolio_tx_third_fee_toggle),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    ThirdFeeFields(
                        feeCoin = feeCoinText,
                        onFeeCoin = { feeCoinText = it.take(PortfolioJson.MAX_FEE_COIN) },
                        amount = feeCoinAmountText,
                        onAmount = { feeCoinAmountText = it },
                        price = feeCoinPriceText,
                        onPrice = { feeCoinPriceText = it; feeCoinPriceAuto = false },
                        state = thirdFee,
                        // Gebühr im erhaltenen Coin: ohne Preis, mindert nur die erhaltene Menge
                        receivedCoinNote = if (feeInReceived) stringResource(R.string.portfolio_tx_swap_fee_received, toCoin) else null,
                        consumes = feeConsumes && isSwap,
                    )
                }
                if (noteInLedger) noteField(4.dp)
            }

            // Summe
            if (fields.price && amount != null && price != null) {
                Text(
                    stringResource(R.string.portfolio_tx_total, PortfolioFormat.usdt((amount * price).toDouble())),
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
                TextButton(onClick = requestClose) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    tried = true
                    // Unvollständige Gebühr im dritten Coin: Abschnitt öffnen, damit der Fehler sichtbar ist
                    if (thirdFee == PortfolioTxForm.ThirdFee.INVALID || feeConsumes) showLedger = true
                    if (valid) {
                        val third = thirdFee == PortfolioTxForm.ThirdFee.COMPLETE || thirdFee == PortfolioTxForm.ThirdFee.INCOMPLETE
                        // Gebühr im dritten Coin, die diese Art im Blatt nicht zeigt (CSV-Import): bei gleicher Art behalten
                        val keepThird = !fields.thirdCoinFee && isEdit && type == initial.type && initial.feeCoin != null
                        saving = true
                        viewModel.save(
                            // Nur die Felder der gewählten Art; Herkunft (CSV-Import) bleibt erhalten
                            initial.copy(
                                coin = coin,
                                type = type,
                                amount = amount,
                                priceUsdt = if (fields.price) price else null,
                                time = time,
                                note = note.trim().takeIf { it.isNotEmpty() },
                                fee = if (keepStoredFee) initial.fee else fee?.takeIf { it.signum() > 0 },
                                feeUnit = if (keepStoredFee) initial.feeUnit else effectiveFeeUnit,
                                exchange = if (fields.exchange) exchange.trim().takeIf { it.isNotEmpty() } else null,
                                transferFrom = if (fields.route) transferFrom.trim().takeIf { it.isNotEmpty() } else null,
                                transferTo = if (fields.route) transferTo.trim().takeIf { it.isNotEmpty() } else null,
                                giftToSpouse = type == PortfolioTxType.GIFT && giftToSpouse,
                                interest = type == PortfolioTxType.REWARD && interest,
                                // Gebühr-Zeile bleibt eine, solange die Art «Verkauf» bleibt
                                feeRow = type == PortfolioTxType.SELL && initial.feeRow,
                                feeCoin = if (keepThird) initial.feeCoin else if (third) feeCoinSymbol else null,
                                feeCoinAmount = if (keepThird) initial.feeCoinAmount else if (third) feeCoinAmount else null,
                                feeCoinPriceUsdt = if (keepThird) initial.feeCoinPriceUsdt else if (third && !feeInReceived) feeCoinPrice else null,
                                // Nur bei einer Einzahlung, nie nach ihrem Datum
                                acquiredTime = if (type == PortfolioTxType.DEPOSIT) acquiredTime?.coerceAtMost(time) else null,
                                // Tausch: erhaltener Coin, Menge und Wert (USDT; leer = unbekannt)
                                toCoin = if (isSwap) toCoin else null,
                                toAmount = if (isSwap) toAmount else null,
                                swapValueUsdt = if (isSwap) swapValue?.takeIf { it.signum() >= 0 } else null,
                            ),
                            onAskEnablePortfolio,
                            onSaved = onDismiss,
                            // Schreiben fehlgeschlagen: Blatt bleibt offen mit den Eingaben, Meldung im Blatt
                            onFailed = {
                                saving = false
                                scope.launch { saveErrorHost.showSnackbar(saveFailedText) }
                            },
                        )
                    }
                }, enabled = !saving) { Text(stringResource(R.string.action_save)) }
            }
        }
        SnackbarHost(saveErrorHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
