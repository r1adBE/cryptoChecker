import SwiftUI

/// «Krypto-Markt» als erste Zeile unter «Daten»: rechts die gesamte Marktkapitalisierung mit
/// 24-h-Veränderung, darunter das 24-h-Volumen, in der Umrechnungswährung (sonst USD). Lädt
/// mit der Dominanz (eine CoinGecko-Abfrage). Beim Laden ein form-gleicher Platzhalter, ohne
/// Daten «gerade nicht verfügbar» mit «Erneut». Tippen zeigt die Quelle.
struct CycleMarketCapRow: View {
    let state: CycleLoad<GlobalMarket>
    /// Umrechnungswährung (`portfolioCurrency`).
    let currency: String
    let onRetry: () -> Void
    var divider = false
    /// Herkunft und Stand (CoinGecko) für die Nebenzeile.
    var stamp: DataStamp? = nil
    @State private var expanded = false
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let market = state.value
        let values = market?.values(currency: currency)
        let capText = values.map { CycleFormat.compactMoney($0.marketCap, $0.code) }
        let volumeText = values.map { CycleFormat.compactMoney($0.volume, $0.code) }
        let change = market?.change24hPercent
        let formatted = PriceFormat.changePercent(change)
        let rowState: MarketRowState = state.isLoading ? .loading
            // Fehler oder keine Werte (auch nicht in USD)
            : (capText == nil ? .failed(L("pulse_unavailable")) : .shown)
        MarketRow(
            title: L("market_cap_title"),
            secondary: volumeText.map { "\(L("market_volume_label")) \($0)" } ?? "",
            value: capText,
            state: rowState,
            divider: divider,
            change: change.map { c in formatted.map { "\(PriceFormat.changeArrow(c)) \($0)" } ?? PriceFormat.zeroPercent() },
            // Vorzeichen und Pfeil folgen der Richtung, die Farbe den Kursfarben (Schema, Kontrast, Tausch)
            changeColor: change.flatMap { c in formatted.map { _ in priceColors.forChange(c, highContrast: highContrast, inverted: inverted) } }
                ?? AppColors.onSurfaceVariant,
            // VoiceOver: ein Satz — Titel, Marktkapitalisierung mit Veränderung, Volumen
            spoken: capText.map { cap in
                A11y.join([
                    L("market_cap_title"),
                    "\(L("market_cap_label")) \(cap)",
                    A11y.change(change),
                    "\(L("market_volume_label")) \(volumeText ?? "")",
                ])
            },
            stamp: stamp,
            onRetry: onRetry,
            expanded: capText == nil ? nil : $expanded,
            details: AnyView(CycleSourceText(text: L("market_cap_source")))
        )
    }
}
