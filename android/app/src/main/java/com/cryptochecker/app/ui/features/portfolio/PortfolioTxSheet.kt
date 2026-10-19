@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import kotlinx.coroutines.launch

/**
 * Transaktion erfassen bzw. bearbeiten (id ≠ 0): Kauf, Verkauf, Umbuchung, Belohnung, Ein-/Auszahlung —
 * Felder und Prüfung je Art nach [PortfolioTxForm.fields] (Regeln in PortfolioLedger, Prüfung [PortfolioTxForm.check]).
 * Preis in USDT, wird nach der Coin-Wahl mit dem aktuellen Kurs vorbelegt
 * (nur bei neuen Einträgen von heute, [PortfolioTxForm]); leer = Preis unbekannt. Belohnung/Einzahlung an
 * einem vergangenen Tag: geprüfter Tagesschluss vorbelegt (markiert).
 * Eingaben und Abläufe in [TxFormState], die Abschnitte in `PortfolioTxSections.kt`; hier Rahmen, Rückfragen und Effekte.
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

    val state = rememberTxFormState(initial)
    val isEdit = state.isEdit
    val currency by viewModel.currency.collectAsState()
    var pickAcquired by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    val d = state.derive(currency)
    val coin = d.coin
    val coinValid = d.coinValid
    val dirtyNow by rememberUpdatedState(d.dirty)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow) { askDiscard = true; false } else true
        },
    )
    // Abbrechen bzw. Schliessen: mit Änderungen erst nachfragen
    val requestClose: () -> Unit = { if (d.dirty) { askDiscard = true } else { onDismiss() } }
    // Speichern läuft (kein Doppeltipp); Fehler beim Speichern als Meldung im Blatt (über dem Blatt sichtbar)
    var saving by remember { mutableStateOf(false) }
    val saveErrorHost = remember { SnackbarHostState() }
    val saveFailedText = stringResource(R.string.portfolio_tx_save_failed)
    val scope = rememberCoroutineScope()

    // Kurs vorbelegen, sobald ein (anderer) Coin feststeht; ein getippter Preis bleibt,
    // der Kurs dient dann nur als Hinweis für eine mehrdeutige Eingabe. Nicht für einen anderen Tag.
    LaunchedEffect(coin, coinValid) {
        state.prefillPrice(coin, coinValid) { viewModel.currentPrice(it) }
    }

    // Belohnung/Einzahlung an einem vergangenen Tag: geprüften Tagesschluss vorbelegen (markiert)
    LaunchedEffect(state.type, coin, coinValid, state.time) {
        state.prefillHistoricClose(coin, coinValid) { c, day -> viewModel.historicClose(c, day) }
    }

    // Gebühr-Coin: Preis mit dem aktuellen Kurs vorbelegen — nur bei einer Transaktion von heute
    val feeCoinSymbol = d.feeCoinSymbol
    LaunchedEffect(feeCoinSymbol) {
        state.prefillFeeCoinPrice(feeCoinSymbol, coin) { viewModel.currentPrice(it) }
    }

    // K-2: Tageskurs USD → Anzeigewährung für das Datum der Transaktion (heute: aktueller Kurs), nur in der Anzeigewährung
    val day = d.day
    LaunchedEffect(currency, day, state.entryFiat) {
        state.loadEntryFx(currency, day) { c, dd -> viewModel.entryFx(c, dd) }
    }

    if (pickDate) {
        DateDialog(
            selected = state.time,
            onSelect = { date ->
                pickDate = false
                state.onDateSelected(date, coinValid, coin)
            },
            onDismiss = { pickDate = false }
        )
    }

    if (pickAcquired) {
        DateDialog(
            selected = state.acquiredTime ?: state.time,
            maxDate = localDate(state.time),
            onSelect = { date ->
                state.onAcquiredSelected(date)
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
        BackHandler(enabled = d.dirty) { askDiscard = true }
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
                TxTypeSection(state)
                TxCoinSection(state, d, coins, onPicked = { focus.clearFocus() })
                // Datum (nicht in der Zukunft) — vor Menge und Preis: ein Datumswechsel kann den Preis leeren,
                // das soll nicht unbemerkt über dem sichtbaren Bereich geschehen
                TxDateSection(state, onPickDate = { pickDate = true }, onPickAcquired = { pickAcquired = true })
                TxAmountSection(state, d, viewModel)
                // Tausch: erhaltener Coin und Menge
                if (d.isSwap) TxSwapReceivedSection(state, d, coins)
                // K-2: Preis/Wert in USDT oder in der Anzeigewährung (umgerechnet mit dem Tageskurs, gespeichert in USDT)
                if (d.fiatPossible) TxEntryCurrencySection(state, d, currency)
                TxPriceValueSection(state, d, currency)
                // Gespeicherter Zugang ohne Preis: Vorschlag Tagesschluss bzw. «Wert bei Zufluss» (Build 84)
                TxCostSuggestion(state, coin, coinValid, d.amount, viewModel)
                // Notiz direkt unter dem Preis, bei allen Arten (Entscheid 18. Oktober 2026)
                TxNoteField(state, 12.dp)
                TxSwitchesSection(state)
                // Umbuchung: von / nach
                if (d.fields.route) TxRouteSection(state, usedWallets, exchanges)
                TxLedgerSection(state, d, exchanges, noteInLedger = false)
                TxTotalLine(d)
                TxActionsRow(
                    isEdit = isEdit,
                    saving = saving,
                    onDelete = { askDelete = true },
                    onCancel = requestClose,
                    onSave = {
                        state.tried = true
                        // Unvollständige Gebühr im dritten Coin: Abschnitt öffnen, damit der Fehler sichtbar ist
                        if (d.thirdFee == PortfolioTxForm.ThirdFee.INVALID || d.feeConsumes) state.showLedger = true
                        if (d.valid) {
                            saving = true
                            viewModel.save(
                                state.toDraft(d),
                                onAskEnablePortfolio,
                                onSaved = onDismiss,
                                // Schreiben fehlgeschlagen: Blatt bleibt offen mit den Eingaben, Meldung im Blatt
                                onFailed = {
                                    saving = false
                                    scope.launch { saveErrorHost.showSnackbar(saveFailedText) }
                                },
                            )
                        }
                    },
                )
            }
            SnackbarHost(saveErrorHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
