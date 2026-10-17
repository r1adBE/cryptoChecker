// Erfassen-Blatt aus der Merkliste bzw. dem Markt (Coin und Kurs vorbelegt) samt Rückfrage «Portfolio-Tab einschalten?».
// Eigene Datei, weil sie das Transaktions-Blatt nur einbettet (wie `PortfolioQuickAdd.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal

/**
 * Erfassen-Blatt aus der Merkliste bzw. dem Markt: Coin und Kurs vorbelegt. Auch mit ausgeschaltetem Portfolio.
 * Build 46: Die erste Transaktion schaltet es nicht still ein — nach dem Speichern ersetzt die Rückfrage
 * «Portfolio-Tab einschalten?» das Blatt: Einschalten → [onPortfolioEnabled]; Später → Transaktion bleibt,
 * Tab bleibt aus, einmal der Weg dorthin ([onPortfolioLater]).
 */
@Composable
fun PortfolioQuickAddSheet(
    coin: String,
    priceUsdt: Double?,
    onDismiss: () -> Unit,
    onPortfolioEnabled: () -> Unit = {},
    onPortfolioLater: () -> Unit = {},
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    var askEnable by rememberSaveable { mutableStateOf(false) }
    if (askEnable) {
        val later = {
            askEnable = false
            onPortfolioLater()
            onDismiss()
        }
        AlertDialog(
            onDismissRequest = later,
            title = { Text(stringResource(R.string.portfolio_enable_ask_title)) },
            text = { Text(stringResource(R.string.portfolio_enable_ask_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askEnable = false
                    viewModel.enablePortfolio(onPortfolioEnabled)
                    onDismiss()
                }) { Text(stringResource(R.string.alarms_notifications_turn_on)) }
            },
            dismissButton = { TextButton(onClick = later) { Text(stringResource(R.string.battery_later)) } },
        )
        return
    }
    PortfolioTxSheet(
        initial = TxDraft(coin = PortfolioCalculator.normalizeCoin(coin), priceUsdt = PortfolioDecimal.of(priceUsdt)),
        viewModel = viewModel,
        // Mit Rückfrage bleibt der Aufrufer offen, bis sie beantwortet ist
        onDismiss = { if (!askEnable) onDismiss() },
        onAskEnablePortfolio = { askEnable = true },
    )
}
