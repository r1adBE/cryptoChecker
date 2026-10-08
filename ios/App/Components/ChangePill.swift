import SwiftUI

extension View {
    /// Die Kapsel aller Kurs-Pillen (Merkliste, Aktionsblatt, «Warum?», Portfolio, Puls-Zeile):
    /// Fläche in `color` mit 14 % Tönung, Innenabstand 8 × 2 pt (gross: 8 × 4) — wie
    /// `Modifier.changePill` in Android. Einzige Stelle, die sie zeichnet.
    func changePillBackground(_ color: Color, large: Bool = false) -> some View {
        self
            .padding(.horizontal, Spacing.sm)
            .padding(.vertical, large ? 4 : 2)
            .background(color.opacity(0.14), in: Capsule())
    }
}

/// Prozent-Änderung als Pille in der Kursfarbe (Grün/Rot bzw. Blau/Orange), immer mit Vorzeichen
/// und Pfeil — die Bedeutung hängt nie allein an der Farbe. Praktisch keine Änderung: graues
/// «0.00%». Ohne Wert: mit `dashWhenMissing` eine graue Pille «—», sonst nichts — wie
/// `ChangePill` in Android. VoiceOver: Richtungswort statt «+»/«−».
struct ChangePill: View {
    let change: Double?
    var large = false
    var dashWhenMissing = false
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted

    var body: some View {
        let value = change.flatMap { $0.isFinite ? $0 : nil }
        if value != nil || dashWhenMissing {
            let formatted = value.flatMap { PriceFormat.changePercent($0) }
            let color = colorFor(value, formatted: formatted)
            HStack(spacing: 3) {
                if let value, formatted != nil {
                    Image(systemName: value >= 0 ? "arrow.up.right" : "arrow.down.right")
                        .scaledFont(size: large ? 11 : 9, weight: .bold, relativeTo: .caption)
                }
                Text(value == nil ? "—" : (formatted ?? PriceFormat.zeroPercent()))
                    .font(AppFont.amount(large ? .subheadline : .caption, weight: .semibold))
            }
            .foregroundStyle(color)
            .lineLimit(1)
            .changePillBackground(color, large: large)
            .contentTransition(.numericText())
            // Pille wächst mit der Textgrösse, aber nicht über AX2 (sonst sprengt sie die Zeile)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(value.flatMap { A11y.change($0) } ?? "—")
        }
    }

    private func colorFor(_ value: Double?, formatted: String?) -> Color {
        guard let value, formatted != nil else { return AppColors.onSurfaceVariant }
        return priceColors.forChange(value, highContrast: highContrast, inverted: inverted)
    }
}
