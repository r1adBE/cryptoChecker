import SwiftUI

/// Ein Sprung zum ersten ungültigen Feld; `seq` zählt hoch, damit erneutes Speichern mit demselben Fehler wieder springt.
struct PortfolioTxFocusJump: Equatable {
    let field: PortfolioTxFirstInvalid.Field
    let seq: Int
}

/// «Speichern» mit Fehlern: zum ersten ungültigen Feld rollen, Fokus setzen und den Fehler ansagen
/// (Entscheid in `PortfolioTxFirstInvalid`) — wie `PortfolioTxFocus.kt` (Android).
extension PortfolioTxSheet {

    /// Nächsten Sprung zu `field` anstossen; Gebühren liegen im Abschnitt — erst aufklappen, sonst fehlt das Feld.
    func requestFocusJump(_ field: PortfolioTxFirstInvalid.Field) {
        if field.inLedger { showLedger = true }
        focusJump = PortfolioTxFocusJump(field: field, seq: (focusJump?.seq ?? 0) + 1)
    }

    /// Sprung ausführen: einen Durchlauf abwarten (ein eben aufgeklappter Abschnitt steht erst dann), rollen,
    /// Fokus setzen und höflich ansagen.
    func performFocusJump(_ jump: PortfolioTxFocusJump?, proxy: ScrollViewProxy) {
        guard let jump else { return }
        let target = focusTarget(jump.field)
        let text = L(fieldLabelKey(jump.field)) + ". " + L(fieldErrorKey(jump.field))
        Task { @MainActor in
            await Task.yield()
            withAnimation(.easeInOut(duration: 0.25)) { proxy.scrollTo(target, anchor: .center) }
            focusedField = target
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// Eingabefeld (Fokus und Roll-Ziel `.id`) zum Fehlerfeld.
    func focusTarget(_ field: PortfolioTxFirstInvalid.Field) -> PortfolioTxField {
        switch field {
        case .coin: return .coin
        case .amount: return .amount
        case .toCoin: return .toCoin
        case .toAmount: return .toAmount
        case .swapValue: return .swapValue
        case .price: return .price
        case .fee: return .fee
        case .feeCoin: return .feeCoin
        }
    }

    /// Beschriftung des Felds (wie im Blatt), für die Ansage.
    private func fieldLabelKey(_ field: PortfolioTxFirstInvalid.Field) -> String {
        switch field {
        case .coin: return isSwap ? "portfolio_tx_swap_given_coin" : "portfolio_coin"
        case .amount: return isSwap ? "portfolio_tx_swap_given_amount" : "portfolio_tx_amount"
        case .toCoin: return "portfolio_tx_swap_received_coin"
        case .toAmount: return "portfolio_tx_swap_received_amount"
        case .swapValue: return "portfolio_tx_swap_value"
        case .price:
            switch type {
            case .REWARD, .DEPOSIT: return "portfolio_tx_value_received"
            case .GIFT, .PAYMENT: return "portfolio_tx_market_value"
            default: return "portfolio_tx_price"
            }
        case .fee: return fields.feeWithUnit ? "ledger_fee" : (type == .TRANSFER ? "portfolio_tx_network_fee" : "portfolio_tx_coin_fee")
        case .feeCoin: return "portfolio_tx_third_fee_coin"
        }
    }

    /// Fehlertext unter dem Feld (gleiche Wahl wie die Felder), für die Ansage.
    private func fieldErrorKey(_ field: PortfolioTxFirstInvalid.Field) -> String {
        switch field {
        case .coin: return "portfolio_coin_invalid"
        case .toCoin: return coinValid && toCoin == coin ? "portfolio_tx_swap_same_coin" : "portfolio_coin_invalid"
        case .price:
            if priceZero { return "portfolio_tx_price_zero" }
            return fiatActive && fxRate == nil ? "portfolio_tx_fiat_no_fx" : "portfolio_tx_price_invalid"
        // Gebühr, Tauschwert und Preis der Dritt-Coin-Gebühr dürfen 0 oder leer sein: nicht «grösser als 0» verlangen
        case .fee: return feeValid ? "portfolio_tx_fee_consumes_amount" : "portfolio_tx_price_invalid"
        case .feeCoin:
            if thirdFee != .invalid && feeConsumes { return "portfolio_tx_fee_consumes_amount" }
            // Gebühr im erhaltenen Coin: nur die Menge, die grösser als 0 sein muss
            return feeInReceived ? "portfolio_invalid" : "portfolio_tx_price_invalid"
        case .swapValue: return "portfolio_tx_price_invalid"
        case .amount, .toAmount: return "portfolio_invalid"
        }
    }
}
