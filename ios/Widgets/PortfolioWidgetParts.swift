import SwiftUI
import WidgetKit

// Bausteine des Portfolio-Widgets, die alle Ansichten teilen (eigene Datei, damit `PortfolioWidgetHome.swift`
// kurz bleibt).

extension PortfolioHomeView {
    /// «≈ 92’310.00 USDT» (je Widget wählbar), kleiner und schwächer, dazu «ohne 1 Coin» (Coins ohne
    /// Kurs zählen im Gesamtwert als 0); wird es eng, bleibt nur der Hinweis. Beides nil = keine Zeile.
    @ViewBuilder
    func usdtLine(_ usdt: String?, missing: String?) -> some View {
        if let usdt, let missing {
            ViewThatFits(in: .horizontal) {
                secondLine("≈ " + usdt + " · " + missing)
                secondLine(missing)
            }
            .privacySensitive()
            .padding(.top, 1)
        } else if let usdt {
            secondLine("≈ " + usdt)
                .privacySensitive()
                .padding(.top, 1)
        } else if let missing {
            secondLine(missing)
                .padding(.top, 1)
        }
    }

    fileprivate func secondLine(_ text: String) -> some View {
        Text.quoted(text, quote: "USDT", size: 12, weight: .medium, quoteColor: palette.secondary)
            .monospacedDigit()
            .foregroundStyle(palette.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }
}
