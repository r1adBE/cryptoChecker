import SwiftUI

// Umbuchungs-Hinweis (`AddressWithdrawalMatch`) mit seiner einzigen Aktion «Ziel ändern» samt Rückfrage — an Adresszeile
// und Coin-Karte gleich. Wie `PortfolioRetargetHint.kt`.

/// «Ziel ändern» unter dem Hinweis «Gleiche Menge wie deine Umbuchung nach «Ledger» …»; öffnet die Rückfrage
/// (`retargetConfirmation`).
struct PortfolioRetargetButton: View {
    @Environment(\.appAccent) private var accent
    let action: () -> Void

    var body: some View {
        Button(L("portfolio_address_retarget"), action: action)
            .font(.subheadline.weight(.semibold))
            .buttonStyle(.borderless)
            .tint(accent.primary)
            .frame(minHeight: 44)
    }
}

enum PortfolioRetarget {
    /// Rückfrage «Umbuchung 1 BTC: Ziel «Ledger» → «Cold»?»; `hideAmounts`: Menge als «•••». Wie Android `RetargetHint`.
    static func question(_ r: AddressWithdrawalMatch.Retarget, hideAmounts: Bool) -> String {
        let amount = PortfolioFormat.exactAmount(r.tx.amountExact, PortfolioCalculator.normalizeCoin(r.tx.coin))
        return L("portfolio_address_retarget_ask", PortfolioInsights.mask(amount, hidden: hideAmounts), r.from, r.to)
    }

    /// Umbuchung `id` aus `txs` mit Ziel `to` (gekürzt wie im Feld, `PortfolioTxForm.maxRoute`); keine Umbuchung/leer:
    /// nil — wie Android `retargeted`.
    static func retargeted(_ txs: [PortfolioTx], id: Int64, to: String) -> PortfolioTx? {
        let target = String(to.trimmingCharacters(in: .whitespacesAndNewlines).prefix(PortfolioTxForm.maxRoute))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty, var tx = txs.first(where: { $0.id == id && $0.type == .TRANSFER }) else { return nil }
        tx.transferTo = target
        return tx
    }

    /// «Ziel ändern» bestätigt: Ziel-Wallet der genannten Umbuchung auf die Adress-Wallet — derselbe Schreibweg wie Ändern
    /// im Blatt (`AppData.savePortfolioTx`, gleiche Prüfung); danach zählt nichts doppelt, der Hinweis entfällt. Nicht
    /// geschrieben (oder Umbuchung fehlt inzwischen): Banner statt still — wie Android `retargetTransfer`.
    @MainActor static func apply(_ r: AddressWithdrawalMatch.Retarget, data: AppData,
                                 banner: Binding<WatchlistBannerMessage?>) {
        Task { @MainActor in
            let saved: Int64?
            if let tx = retargeted(data.portfolio, id: r.tx.id, to: r.to) {
                saved = await data.savePortfolioTx(tx)
            } else {
                saved = nil
            }
            if saved == nil {
                Haptics.play(.error)
                banner.wrappedValue = .portfolioNotWritten(data)
            }
        }
    }
}

extension View {
    /// Rückfrage zu «Ziel ändern» (Ändern / Abbrechen) für `item`; Ändern ruft `onConfirm`. Wie Android `RetargetHint`.
    func retargetConfirmation(_ item: Binding<AddressWithdrawalMatch.Retarget?>, hideAmounts: Bool,
                              onConfirm: @escaping (AddressWithdrawalMatch.Retarget) -> Void) -> some View {
        alert(Text(item.wrappedValue.map { PortfolioRetarget.question($0, hideAmounts: hideAmounts) } ?? ""),
              isPresented: Binding(get: { item.wrappedValue != nil }, set: { if !$0 { item.wrappedValue = nil } }),
              presenting: item.wrappedValue) { r in
            Button(L("portfolio_address_retarget_confirm")) { onConfirm(r) }
                .keyboardShortcut(.defaultAction)
            Button(L("action_cancel"), role: .cancel) {}
        }
    }
}
