// Werte eines Durchgangs im Erfassen-Blatt ([TxFormState.derive]): gelesene Felder, Prüfung und Änderungen.
// Eigene Datei, weil `PortfolioTxFormState` (Eingaben und Abläufe) sonst zu lang würde.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.Stable
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import java.math.BigDecimal
import java.time.LocalDate

/** Abgeleitete Werte eines Durchgangs ([TxFormState.derive]); die Prüfung selbst in [check]. */
@Stable
internal class TxFormDerived(
    val check: PortfolioTxForm.Check,
    /** Menge > 0; null = leer, unlesbar oder ≤ 0. */
    val amount: BigDecimal?,
    /** Tag der Transaktion (Ortszeit). */
    val day: LocalDate,
    /** Eingabe in der Anzeigewährung möglich (Preis-/Wertfeld, nicht USD). */
    val fiatPossible: Boolean,
    /** Geladener Devisenkurs dieses Tags; null = (noch) keiner. */
    val fxRate: BigDecimal?,
    /** Felder stehen in der Anzeigewährung. */
    val fiatActive: Boolean,
    /** Preis in USDT. */
    val price: BigDecimal?,
    val toAmount: BigDecimal?,
    /** Tausch: Wert in USDT. */
    val swapValue: BigDecimal?,
    val keepStoredFee: Boolean,
    val feeCoinSymbol: String,
    val feeCoinAmount: BigDecimal?,
    val feeCoinPrice: BigDecimal?,
    val isToday: Boolean,
    val dirty: Boolean,
    val type: PortfolioTxType,
) {
    val fields: PortfolioTxForm.Fields get() = check.fields
    val isSwap: Boolean get() = type == PortfolioTxType.SWAP
    val coin: String get() = check.coin
    val coinValid: Boolean get() = check.coinValid
    val priceZero: Boolean get() = check.priceZero
    val priceValid: Boolean get() = check.priceValid
    val toCoin: String get() = check.toCoin
    val toCoinValid: Boolean get() = check.toCoinValid
    val swapValueValid: Boolean get() = check.swapValueValid
    val hasFee: Boolean get() = check.hasFee
    /** Gebühr dieser Art (null = leer, unlesbar oder Art ohne Gebühr). */
    val fee: BigDecimal? get() = check.fee
    val feeValid: Boolean get() = check.feeValid
    val effectiveFeeUnit: PortfolioFeeUnit get() = check.feeUnit
    val feeInReceived: Boolean get() = check.feeInReceived
    val thirdFee: PortfolioTxForm.ThirdFee get() = check.thirdFee
    val feeConsumes: Boolean get() = check.feeConsumes
    val valid: Boolean get() = check.valid
}
