// Felder und Knöpfe des Transaktionsblatts ([PortfolioTxSheet]). Eigene Datei, weil PortfolioTxSheet.kt sonst zu lang
// würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.HapticPlayer
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.resources.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Felder und Knöpfe von [PortfolioTxSheet] (in dessen scrollbarer Spalte); Speichern mit Prüfung und Haptik. */
@Composable
internal fun TxSheetForm(
    state: TxFormState,
    d: TxFormDerived,
    viewModel: PortfolioViewModel,
    coins: List<String>,
    exchanges: List<String>,
    usedWallets: List<String>,
    currency: String,
    saving: Boolean,
    saveSession: String,
    focusTargets: TxFocusTargets,
    haptics: HapticPlayer,
    saveErrorHost: SnackbarHostState,
    scope: CoroutineScope,
    onClearFocus: () -> Unit,
    onPickDate: () -> Unit,
    onPickAcquired: () -> Unit,
    onAskDelete: () -> Unit,
    onCancel: () -> Unit,
    onAskEnablePortfolio: () -> Unit,
    onClosedAfterSave: () -> Unit,
    /** Gespeicherter Stand gerade nicht lesbar (Sperre des Speichers, beim Fehlschlag geprüft). */
    isStorageLocked: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val isEdit = state.isEdit
    val coin = d.coin
    val coinValid = d.coinValid
    val saveFailedText = stringResource(R.string.portfolio_tx_save_failed)
    // Gespeicherter Stand gerade nicht lesbar: nichts geschrieben (PortfolioStorageLock) — diesen Grund nennen
    val storageLockedText = stringResource(R.string.portfolio_storage_locked)
    val resources = LocalResources.current
    Text(
        stringResource(if (isEdit) R.string.portfolio_edit_tx else R.string.portfolio_add_tx),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    TxTypeSection(state)
    TxCoinSection(state, d, coins, onPicked = onClearFocus)
    // Datum (nicht in der Zukunft) — vor Menge und Preis: ein Datumswechsel kann den Preis leeren,
    // das soll nicht unbemerkt über dem sichtbaren Bereich geschehen
    TxDateSection(state, onPickDate = onPickDate, onPickAcquired = onPickAcquired)
    TxAmountSection(state, d, viewModel)
    // Tausch: erhaltener Coin und Menge
    if (d.isSwap) TxSwapReceivedSection(state, d, coins)
    // Preis/Wert in USDT oder in der Anzeigewährung (umgerechnet mit dem Tageskurs, gespeichert in USDT)
    if (d.fiatPossible) TxEntryCurrencySection(state, d, currency)
    TxPriceValueSection(state, d, currency)
    // Gespeicherter Zugang ohne Preis: Vorschlag Tagesschluss bzw. «Wert bei Zufluss»
    TxCostSuggestion(state, coin, coinValid, d.amount, viewModel)
    // Notiz direkt unter dem Preis, bei allen Arten
    TxNoteField(state, 12.dp)
    TxSwitchesSection(state)
    // Umbuchung: von / nach
    if (d.fields.route) TxRouteSection(state, usedWallets, exchanges)
    TxLedgerSection(state, d, exchanges, noteInLedger = false)
    TxTotalLine(d)
    TxActionsRow(
        isEdit = isEdit,
        saving = saving,
        onDelete = onAskDelete,
        onCancel = onCancel,
        onSave = {
            state.tried = true
            // Unvollständige Gebühr im dritten Coin: Abschnitt öffnen, damit der Fehler sichtbar ist
            if (d.thirdFee == PortfolioTxForm.ThirdFee.INVALID || d.feeConsumes) state.showLedger = true
            PortfolioTxFirstInvalid.of(d.check, amountValid = d.amount != null, isSwap = d.isSwap)?.let { field ->
                // Gebühren liegen im Abschnitt: erst aufklappen, sonst gibt es kein Feld für den Fokus
                if (field.inLedger) state.showLedger = true
                focusTargets.jumpTo(
                    field,
                    resources.getString(txFieldLabelRes(field, d)) + ". " + resources.getString(txFieldErrorRes(field, d)),
                )
            }
            // Haptik wie iOS: mit Fehlern Warnung, gespeichert Erfolg, Schreiben gescheitert Fehler
            if (!d.valid) haptics.play(HapticEvent.WARNING)
            if (d.valid) {
                viewModel.save(
                    state.toDraft(d),
                    onAskEnablePortfolio,
                    onSaved = {
                        haptics.play(HapticEvent.SUCCESS)
                        onClosedAfterSave()
                        onDismiss()
                    },
                    // Schreiben fehlgeschlagen: Blatt bleibt offen mit den Eingaben, Meldung im Blatt
                    onFailed = {
                        haptics.play(HapticEvent.ERROR)
                        val locked = isStorageLocked()
                        // Lang: Sperr-Hinweis ist länger, Screenreader brauchen die Zeit (wie AlarmsScreen)
                        scope.launch {
                            saveErrorHost.showAppSnackbar(
                                if (locked) storageLockedText else saveFailedText,
                                SnackbarKind.ERROR,
                                duration = SnackbarDuration.Long,
                            )
                        }
                    },
                    session = saveSession,
                )
            }
        },
    )
}
