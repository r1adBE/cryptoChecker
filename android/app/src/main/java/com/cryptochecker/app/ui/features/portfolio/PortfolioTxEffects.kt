package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.cryptochecker.resources.R

/**
 * Vorbelegungen im Erfassen-Blatt ([PortfolioTxSheet]): Kurs nach der Coin-Wahl, geprüfter Tagesschluss an einem
 * vergangenen Tag, Preis des Gebühr-Coins und Tageskurs USD → Anzeigewährung.
 */
@Composable
internal fun TxPrefillEffects(state: TxFormState, d: TxFormDerived, currency: String, viewModel: PortfolioViewModel) {
    val coin = d.coin
    val coinValid = d.coinValid
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

    // Tageskurs USD → Anzeigewährung für das Datum der Transaktion (heute: aktueller Kurs), nur in der Anzeigewährung
    val day = d.day
    LaunchedEffect(currency, day, state.entryFiat) {
        state.loadEntryFx(currency, day) { c, dd -> viewModel.entryFx(c, dd) }
    }
}

/** Rückfrage «Änderungen verwerfen?» der Portfolio-Blätter: Verwerfen (rot) | Weiter bearbeiten. */
@Composable
internal fun DiscardChangesDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.portfolio_discard_title)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.portfolio_discard_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.portfolio_discard_keep)) }
        }
    )
}
