package com.cryptochecker.app.domain.portfolio

/**
 * Erstes ungültiges Feld des Erfassen-Blatts in der Reihenfolge der Oberfläche: dorthin rollt «Speichern» mit
 * Fehlern, setzt den Fokus und sagt den Fehler an (seit Build 99; vorher wurde nur rot markiert, oft ausserhalb
 * des sichtbaren Bereichs). Gleich wie `PortfolioTxFirstInvalid.swift` (iOS).
 */
object PortfolioTxFirstInvalid {

    /** Felder, die einen Fehler tragen können — Reihenfolge wie im Blatt (Coin oben, Gebühren im Abschnitt unten). */
    enum class Field {
        COIN, AMOUNT, TO_COIN, TO_AMOUNT, SWAP_VALUE, PRICE,
        /** Gebühr im Abschnitt «Gebühr und Börse». */
        FEE,
        /** Gebühr im dritten (bzw. beim Tausch im erhaltenen) Coin, ebenfalls im Abschnitt. */
        FEE_COIN;

        /** Liegt im eingeklappten Abschnitt: vor dem Fokus aufklappen. */
        val inLedger: Boolean get() = this == FEE || this == FEE_COIN
    }

    /**
     * Erstes Feld mit Fehler; null = alles speicherbar (genau dann, wenn [PortfolioTxForm.Check.valid] und die
     * Menge gültig ist). [amountValid]: Menge > 0 gelesen (steht nicht im Check). Gebühr ≥ erhaltene Menge zeigt
     * der Tausch an der Gebühr im erhaltenen Coin, sonst am Gebührenfeld (wie die Fehler im Blatt).
     */
    fun of(check: PortfolioTxForm.Check, amountValid: Boolean, isSwap: Boolean): Field? = when {
        !check.coinValid -> Field.COIN
        !amountValid -> Field.AMOUNT
        !check.toCoinValid -> Field.TO_COIN
        !check.toAmountValid -> Field.TO_AMOUNT
        !check.swapValueValid -> Field.SWAP_VALUE
        !check.priceValid -> Field.PRICE
        !check.feeValid -> Field.FEE
        check.thirdFee == PortfolioTxForm.ThirdFee.INVALID -> Field.FEE_COIN
        check.feeConsumes -> if (isSwap) Field.FEE_COIN else Field.FEE
        else -> null
    }
}
