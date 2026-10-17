import Accessibility
import SwiftUI

// Eine Transaktionszeile der Coin-Detailseite mit Buchungs- und Tausch-Text.
// Eigene Datei, weil Zeile und Texte zusammengehören und die Detailseite sonst zu lang wird
// (wie `PortfolioTxRow.kt`).

// MARK: Transaktion

/// Transaktion: Art und Datum, Menge × Preis, Notiz, Summe.
struct PortfolioTxRow: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    let tx: PortfolioTx
    /// Coin der Ansicht: Tausch von dort aus «Tausch in ETH» bzw. «Tausch von BTC».
    var coin: String = ""

    var body: some View {
        HStack(spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 0) {
                    Text(swapLabel ?? PortfolioFormat.typeLabel(tx.type))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PortfolioFormat.typeColor(tx.type))
                    Text(verbatim: " · " + PortfolioFormat.date(tx.time))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Text(verbatim: amountLine)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.middle)
                // Gebühr und Börse (D1): «Gebühr 1.20 USDT · Binance», klein und grau
                if let line = ledgerLine {
                    Text(verbatim: PortfolioInsights.mask(line, hidden: hideAmounts))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                if let note = tx.note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            PriceWithQuote.text(total.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
                .font(AppFont.amount(.subheadline, weight: .medium))
                .foregroundStyle(AppColors.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// Summe rechts: Tausch der Wert des Tauschs, sonst Menge × Preis; nil = unbekannt.
    private var total: Double? {
        if tx.type == .SWAP { return tx.swapValue.map(PortfolioDecimal.double) }
        return tx.priceUsdt.map { $0 * tx.amount }
    }

    /// Tausch aus Sicht von `coin`: abgegeben «Tausch in ETH», erhalten «Tausch von BTC»; andere Arten nil — wie Android.
    private var swapLabel: String? {
        guard tx.type == .SWAP, let to = tx.toCoin, !to.isEmpty else { return nil }
        return PortfolioCalculator.normalizeCoin(to) == coin ? L("portfolio_tx_swap_from", tx.coin) : L("portfolio_tx_swap_to", to)
    }

    /// Tausch: «0.1 BTC → 1.6 ETH»; andere Arten nil.
    private var swapLine: String? {
        guard tx.type == .SWAP, let to = tx.toCoin, !to.isEmpty, let received = tx.toAmountExact else { return nil }
        return PortfolioFormat.exactAmount(tx.amountExact, tx.coin) + " → " + PortfolioFormat.exactAmount(received, to)
    }

    /// Mengenzeile: Tausch «0.1 BTC → 1.6 ETH», sonst «Menge × Kurs» (ohne Kurs «Kurs fehlt»); verdeckt mit `hideAmounts`.
    /// In Schritten mit festen Typen (eine lange `??`/`+`-Kette war für den Type-Checker teuer).
    private var amountLine: String {
        if let swapLine { return PortfolioInsights.mask(swapLine, hidden: hideAmounts) }
        let amount: String = PortfolioInsights.mask(PortfolioFormat.exactAmount(tx.amountExact, tx.coin), hidden: hideAmounts)
        let price: String = tx.priceUsdt.map { PortfolioFormat.price($0) } ?? L("portfolio_price_missing")
        return amount + " × " + price
    }

    /// «Gebühr 1.20 USDT · Binance», «Gebühr 0.0001 BTC», «Binance»; nil ohne beides — wie `ledgerLine` (Android).
    private var ledgerLine: String? {
        var parts: [String] = []
        if let fee = PortfolioTx.validFee(tx.feeExact) {
            let unit = (tx.feeUnit ?? .QUOTE) == .QUOTE ? PortfolioFormat.usdt : tx.coin
            parts.append(L("ledger_fee_line", PortfolioFormat.exactAmount(fee, unit)))
        }
        // Gebühr in einem dritten Coin («Gebühr 0.01 BNB»); beim Tausch auch im erhaltenen Coin
        if let feeCoin = tx.thirdFeeCoin {
            parts.append(L("ledger_fee_line", PortfolioFormat.exactAmount(tx.thirdFeeAmount, feeCoin)))
        } else if tx.toCoinFee > 0, let to = tx.swapToCoin {
            parts.append(L("ledger_fee_line", PortfolioFormat.exactAmount(tx.toCoinFee, to)))
        }
        // Umbuchung: «Binance → Ledger» (fehlt eine Seite: «—»); Schenkung an Ehe-/Lebenspartner (eigenes Feld)
        let from = tx.transferFrom.flatMap { $0.isEmpty ? nil : $0 }
        let to = tx.transferTo.flatMap { $0.isEmpty ? nil : $0 }
        if tx.isGiftToSpouse {
            parts.append(L("portfolio_tx_gift_spouse"))
        } else if from != nil || to != nil {
            parts.append("\(from ?? "—") → \(to ?? "—")")
        }
        if let exchange = tx.exchange, !exchange.isEmpty { parts.append(exchange) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}
