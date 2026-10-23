import Foundation

/// Erstes ungültiges Feld des Erfassen-Blatts in der Reihenfolge der Oberfläche: dorthin rollt «Speichern» mit
/// Fehlern, setzt den Fokus und sagt den Fehler an (statt nur rot zu markieren, oft ausserhalb
/// des sichtbaren Bereichs). Gleich wie `PortfolioTxFirstInvalid.kt` (Android).
enum PortfolioTxFirstInvalid {

    /// Felder, die einen Fehler tragen können — Reihenfolge wie im Blatt (Coin oben, Gebühren im Abschnitt unten).
    enum Field: Equatable {
        case coin, amount, toCoin, toAmount, swapValue, price
        /// Gebühr im Abschnitt «Gebühr und Börse».
        case fee
        /// Gebühr im dritten (bzw. beim Tausch im erhaltenen) Coin, ebenfalls im Abschnitt.
        case feeCoin

        /// Liegt im eingeklappten Abschnitt: vor dem Fokus aufklappen.
        var inLedger: Bool { self == .fee || self == .feeCoin }
    }

    /// Erstes Feld mit Fehler; nil = alles speicherbar (genau dann, wenn `Check.valid` und die Menge gültig ist).
    /// `amountValid`: Menge > 0 gelesen (steht nicht im Check). Gebühr ≥ erhaltene Menge zeigt der Tausch an der
    /// Gebühr im erhaltenen Coin, sonst am Gebührenfeld (wie die Fehler im Blatt).
    static func of(_ check: PortfolioTxForm.Check, amountValid: Bool, isSwap: Bool) -> Field? {
        if !check.coinValid { return .coin }
        if !amountValid { return .amount }
        if !check.toCoinValid { return .toCoin }
        if !check.toAmountValid { return .toAmount }
        if !check.swapValueValid { return .swapValue }
        if !check.priceValid { return .price }
        if !check.feeValid { return .fee }
        if check.thirdFee == .invalid { return .feeCoin }
        if check.feeConsumes { return isSwap ? .feeCoin : .fee }
        return nil
    }
}
