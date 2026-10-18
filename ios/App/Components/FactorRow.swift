import SwiftUI

// MARK: Faktor-Checkliste

/// Eine Zeile der Faktor-Checkliste (Crypto Pulse und Blatt «Was bewegt sich mit?»): Markierung ✓ / – / !, kurzer Titel, Wert rechtsbündig, optional
/// eine Erklärung darunter. VoiceOver: ein Element «Volumen, stützt die
/// Bewegung, 1,34-mal so viel wie üblich». Wie `FactorRow.kt`.
struct FactorRow: View {
    let mark: WhySummary.Mark
    let title: String
    let value: String
    var spokenValue: String? = nil
    var detail: String? = nil

    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(mark.glyph)
                    .font(.subheadline.weight(.heavy))
                    .foregroundStyle(markColor)
                    .frame(width: 20, alignment: .leading)
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if !value.isEmpty {
                    Text(value)
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppColors.onSurface)
                        .multilineTextAlignment(.trailing)
                        .padding(.leading, 12)
                }
            }
            if let detail {
                Text(detail)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, Spacing.lg)
            }
        }
        .padding(.vertical, Spacing.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
    }

    private var spoken: String {
        let row = A11y.join([title, L(mark.a11yKey), spokenValue ?? value])
        guard let detail else { return row }
        return row + ". " + detail
    }

    /// ✓ in der «OK»-Farbe (Steigend-Farbe des Schemas, nie getauscht), ! in Bernstein, – grau.
    private var markColor: Color {
        switch mark {
        case .supports: priceColors.up(highContrast: highContrast)
        case .caution: AppColors.warningText
        case .neutral: AppColors.onSurfaceVariant
        }
    }
}
