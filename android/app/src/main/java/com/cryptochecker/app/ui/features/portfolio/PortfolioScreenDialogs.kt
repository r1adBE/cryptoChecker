// Blätter und Dialoge des Portfolio-Tabs (Erfassen, Adressen, Import, Export, Alarm, Leeren, Umrechnungswährung).
// Eigene Datei, weil `PortfolioScreen` sonst zu lang würde; ob ein Dialog offen ist, hält der Bildschirm.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.notification.PortfolioAlarmTexts
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.ui.features.watchlist.WatchlistBanner
import kotlinx.coroutines.flow.map
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.theme.SnackbarKind

/**
 * Blätter und Dialoge des Portfolio-Tabs: Erfassen, Adressen, CSV-Import, Importe, Umrechnungswährung, Export,
 * Alarm «Portfolio-Wert» und «Portfolio leeren». Ob sie offen sind, hält der Bildschirm (teils rememberSaveable).
 */
@Composable
internal fun PortfolioScreenDialogs(
    viewModel: PortfolioViewModel,
    banner: WatchlistBanner,
    transactions: List<PortfolioTxEntity>?,
    currency: String,
    changeBasis: ChangeBasis,
    hideAmounts: Boolean,
    sheet: TxDraft?,
    onSheetChange: (TxDraft?) -> Unit,
    addressesOpen: Boolean,
    onAddressesOpenChange: (Boolean) -> Unit,
    csvImportOpen: Boolean,
    onCsvImportOpenChange: (Boolean) -> Unit,
    importsOpen: Boolean,
    onImportsOpenChange: (Boolean) -> Unit,
    pickCurrency: Boolean,
    onPickCurrencyChange: (Boolean) -> Unit,
    exportOpen: Boolean,
    onExportOpenChange: (Boolean) -> Unit,
    alarmOpen: Boolean,
    onAlarmOpenChange: (Boolean) -> Unit,
    askClear: Boolean,
    onAskClearChange: (Boolean) -> Unit,
) {
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Nicht geschrieben: gesperrt bzw. Fehler nennen statt still (wie iOS)
    val notSaved = { result: PortfolioWriteResult ->
        banner.show(resources.getString(result.notSavedText()), SnackbarKind.ERROR)
    }
    // Ungültige Einträge aus der Systemsicherung einmal nennen
    SystemRestoreNoticeDialog()
    sheet?.let { draft ->
        PortfolioTxSheet(initial = draft, viewModel = viewModel, onDismiss = { onSheetChange(null) })
    }
    if (addressesOpen) {
        PortfolioAddressSheet(
            viewModel = viewModel,
            // Erste Adresse mit ausgeschaltetem Portfolio schaltet es ein (wie die erste Transaktion)
            onPortfolioEnabled = { banner.show(resources.getString(R.string.portfolio_enabled_banner)) },
            onDismiss = { onAddressesOpenChange(false) }
        )
    }
    if (csvImportOpen) {
        PortfolioCsvImportFlow(
            onDismiss = { onCsvImportOpenChange(false) },
            onImported = { count ->
                val text = resources.getQuantityString(R.plurals.portfolio_csv_import_done, count, count)
                banner.show(text, SnackbarKind.SUCCESS)
            },
            // Erste Transaktion mit ausgeschaltetem Portfolio schaltet es ein (wie von Hand)
            onPortfolioEnabled = { banner.show(resources.getString(R.string.portfolio_enabled_banner)) },
        )
    }
    if (importsOpen) {
        PortfolioImportsDialog(
            batches = PortfolioImportBatch.summarize(transactions.orEmpty().map { it.importBatch }),
            onUndo = { batch ->
                // Nicht geschrieben: Hinweis statt still
                viewModel.undoImport(batch.id, onFailed = notSaved) { removed ->
                    haptics.play(HapticEvent.DESTRUCTIVE)
                    banner.show(resources.getQuantityString(R.plurals.portfolio_import_undone, removed.size, removed.size)) {
                        viewModel.restore(removed) { if (it != PortfolioWriteResult.OK) notSaved(it) }
                    }
                }
            },
            onDismiss = { onImportsOpenChange(false) },
        )
    }
    if (pickCurrency) {
        CurrencyDialog(
            selected = currency,
            onSelect = { viewModel.setCurrency(it); onPickCurrencyChange(false) },
            onDismiss = { onPickCurrencyChange(false) }
        )
    }

    if (exportOpen) {
        PortfolioExportDialog(
            currency = currency,
            onDismiss = { onExportOpenChange(false) },
            // Nur nach gelungenem Export (Fehler zeigt der Dialog selbst)
            onDone = { banner.show(it, SnackbarKind.SUCCESS) },
        )
    }
    if (alarmOpen) {
        PortfolioAlarmDialog(
            currency = currency,
            basis = changeBasis,
            onSave = { kind, threshold, repeating ->
                viewModel.addAlarm(kind, threshold, repeating) { alarm, allowed ->
                    haptics.play(HapticEvent.SUCCESS)
                    // «Alarm gespeichert: Portfolio über 50’000.00 CHF · einmalig» (· Mitteilungen aus)
                    val off = !allowed
                    val condition = PortfolioAlarmTexts.sentence(context, alarm, changeBasis, hideAmounts)
                    val text = com.cryptochecker.app.ui.features.alarms.savedText(context, condition, alarm.repeating, off)
                    if (off) {
                        // Info statt Erfolg: gespeichert, kommt aber nicht an (Mitteilungen aus)
                        banner.showAction(
                            text, resources.getString(R.string.settings_title), SAVED_ALARM_MILLIS, SnackbarKind.INFO,
                        ) {
                            com.cryptochecker.app.ui.components.openAppNotificationSettings(context)
                        }
                    } else {
                        banner.show(text, SnackbarKind.SUCCESS)
                    }
                }
                onAlarmOpenChange(false)
            },
            onDismiss = { onAlarmOpenChange(false) }
        )
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { onAskClearChange(false) },
            title = { Text(stringResource(R.string.portfolio_clear)) },
            // Anzahl nennen; beobachtete Adressen bleiben (nur Transaktionen werden gelöscht); «Rückgängig» danach
            text = {
                DialogText {
                    val count = transactions?.size ?: 0
                    Text(pluralStringResource(R.plurals.portfolio_clear_confirm_count, count, count))
                }
            },
            confirmButton = {
                // Danach «Rückgängig» im Banner (alle Transaktionen mit ihren ids zurück), wie beim Löschen eines Coins
                TextButton(onClick = {
                    onAskClearChange(false)
                    // Haptik erst nach dem Löschen und nur, wenn etwas weg ist (wie iOS); nicht geschrieben: Hinweis
                    viewModel.clearAll(onFailed = notSaved) { removed ->
                        haptics.play(HapticEvent.DESTRUCTIVE)
                        banner.show(resources.getQuantityString(R.plurals.portfolio_cleared, removed.size, removed.size)) {
                            viewModel.restore(removed) { if (it != PortfolioWriteResult.OK) notSaved(it) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.portfolio_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onAskClearChange(false) }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/**
 * Rückfrage vor dem Löschen eines ganzen Coins (Wischen oder Screenreader-Aktion «Löschen»): nennt die Zahl
 * [count] der Transaktionen, die mitgehen — ein Wisch soll nicht unbemerkt viele Einträge löschen — und die Zahl
 * [feeRefs] der Transaktionen anderer Coins, die ihre Gebühr in diesem Coin verlieren ([PortfolioCoinDeletion]).
 */
@Composable
internal fun DeleteCoinDialog(coin: String, count: Int, feeRefs: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Haptik erst nach dem Löschen ([PortfolioScreen] deleteCoin), wie iOS
    val confirm = pluralStringResource(R.plurals.portfolio_coin_delete_confirm, count, count)
    val feeText = if (feeRefs > 0) stringResource(R.string.portfolio_coin_delete_fee_refs, coin, feeRefs) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_coin_delete_title, coin)) },
        text = { DialogText { Text(if (feeText != null) "$confirm\n\n$feeText" else confirm) } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Zielwährung der Umrechnungszeile wählen. */
@Composable
private fun CurrencyDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_convert_to)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(FxRateSource.CURRENCIES, key = { it }) { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = code == selected, onClick = { onSelect(code) })
                        Text(code, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/** So lange steht «Alarm gespeichert … · Mitteilungen aus» mit «Einstellungen». */
private const val SAVED_ALARM_MILLIS = 8_000L
