// «Speichern» mit Fehlern im Erfassen-Blatt: zum ersten ungültigen Feld rollen, Fokus setzen und den Fehler ansagen
// (Entscheid in [PortfolioTxFirstInvalid], gleich wie iOS). Eigene Datei, damit das Blatt kurz bleibt.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid.Field
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType

/** Ein Sprung zum Feld [field]; [seq] zählt hoch, damit erneutes Speichern mit demselben Fehler wieder springt. */
internal data class TxFocusRequest(val field: Field, val announcement: String, val seq: Int)

/** Fokus- und Roll-Ziele der Felder mit Fehlern (im Blatt gemerkt, nicht gesichert). */
@Stable
internal class TxFocusTargets {
    private val focus = Field.entries.associateWith { FocusRequester() }
    private val bring = Field.entries.associateWith { BringIntoViewRequester() }
    var request by mutableStateOf<TxFocusRequest?>(null)
        private set

    fun focusOf(field: Field): FocusRequester = focus.getValue(field)
    fun bringOf(field: Field): BringIntoViewRequester = bring.getValue(field)

    fun jumpTo(field: Field, announcement: String) {
        request = TxFocusRequest(field, announcement, (request?.seq ?: 0) + 1)
    }
}

/** Ziele des offenen Erfassen-Blatts; ausserhalb null (dann ohne Wirkung). */
internal val LocalTxFocusTargets = staticCompositionLocalOf<TxFocusTargets?> { null }

/** Feld als Ziel für [field] anmelden; ohne [targets] unverändert. */
internal fun Modifier.txFocusTarget(targets: TxFocusTargets?, field: Field): Modifier =
    if (targets == null) this else bringIntoViewRequester(targets.bringOf(field)).focusRequester(targets.focusOf(field))

/**
 * Führt den Sprung aus: ein Bild abwarten (ein eben aufgeklappter Abschnitt ist erst dann gemessen), Feld in den
 * sichtbaren Bereich rollen, Fokus setzen und den Fehler höflich ansagen (wie iOS `AccessibilityNotification`).
 */
@Composable
internal fun TxFocusFirstInvalidEffect(targets: TxFocusTargets) {
    val view = LocalView.current
    val request = targets.request
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        withFrameNanos { }
        // Feld (noch) nicht angemeldet: nur nicht abstürzen, die Ansage kommt trotzdem
        runCatchingCancellable { targets.bringOf(r.field).bringIntoView() }
        runCatchingCancellable { targets.focusOf(r.field).requestFocus() }
        // Compose kennt keine eigene Ansage; die Fehlertexte als Live-Region würden bei jeder Eingabe sprechen
        @Suppress("DEPRECATION")
        view.announceForAccessibility(r.announcement)
    }
}

/** Beschriftung des Felds (wie im Blatt), für die Ansage. */
internal fun txFieldLabelRes(field: Field, d: TxFormDerived): Int = when (field) {
    Field.COIN -> if (d.isSwap) R.string.portfolio_tx_swap_given_coin else R.string.portfolio_coin
    Field.AMOUNT -> if (d.isSwap) R.string.portfolio_tx_swap_given_amount else R.string.portfolio_tx_amount
    Field.TO_COIN -> R.string.portfolio_tx_swap_received_coin
    Field.TO_AMOUNT -> R.string.portfolio_tx_swap_received_amount
    Field.SWAP_VALUE -> R.string.portfolio_tx_swap_value
    Field.PRICE -> when (d.type) {
        PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> R.string.portfolio_tx_value_received
        PortfolioTxType.GIFT, PortfolioTxType.PAYMENT -> R.string.portfolio_tx_market_value
        else -> R.string.portfolio_tx_price
    }
    Field.FEE -> when {
        d.fields.feeWithUnit -> R.string.ledger_fee
        d.type == PortfolioTxType.TRANSFER -> R.string.portfolio_tx_network_fee
        else -> R.string.portfolio_tx_coin_fee
    }
    Field.FEE_COIN -> R.string.portfolio_tx_third_fee_coin
}

/** Fehlertext unter dem Feld (gleiche Wahl wie die Abschnitte), für die Ansage. */
internal fun txFieldErrorRes(field: Field, d: TxFormDerived): Int = when (field) {
    Field.COIN -> R.string.portfolio_coin_invalid
    Field.TO_COIN -> if (d.coinValid && d.toCoin == d.coin) R.string.portfolio_tx_swap_same_coin else R.string.portfolio_coin_invalid
    Field.PRICE -> when {
        d.priceZero -> R.string.portfolio_tx_price_zero
        d.fiatActive && d.fxRate == null -> R.string.portfolio_tx_fiat_no_fx
        else -> R.string.portfolio_tx_price_invalid
    }
    // Gebühr, Tauschwert und Preis der Dritt-Coin-Gebühr dürfen 0 oder leer sein: nicht «grösser als 0» verlangen
    Field.FEE -> if (d.feeValid) R.string.portfolio_tx_fee_consumes_amount else R.string.portfolio_tx_price_invalid
    Field.FEE_COIN -> when {
        d.thirdFee != PortfolioTxForm.ThirdFee.INVALID && d.feeConsumes -> R.string.portfolio_tx_fee_consumes_amount
        // Gebühr im erhaltenen Coin: nur die Menge, die grösser als 0 sein muss
        d.feeInReceived -> R.string.portfolio_invalid
        else -> R.string.portfolio_tx_price_invalid
    }
    Field.SWAP_VALUE -> R.string.portfolio_tx_price_invalid
    Field.AMOUNT, Field.TO_AMOUNT -> R.string.portfolio_invalid
}
