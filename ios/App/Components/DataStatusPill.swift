import SwiftUI

/// Ton der `DataStatusPill` — gleiche Farben wie die Status-Pille der Merkliste.
enum DataStatusTone: Equatable {
    /// Alles aktuell: ✓ in der Textfarbe.
    case fresh
    /// Neutral (grau, Punkt): offline oder lädt gerade — kein Fehler der App.
    case neutral
    /// Veraltet: ! in der Fehlerfarbe.
    case warn

    /// Aktuell `onSurface` (ruhig, nicht in der Steigen-Farbe: frisch ≠ gestiegen), neutral `onSurfaceVariant`,
    /// veraltet `error` (wie die Merkliste) — wie Android.
    var color: Color {
        switch self {
        case .fresh: AppColors.onSurface
        case .neutral: AppColors.onSurfaceVariant
        case .warn: AppColors.error
        }
    }
}

/// Zustand der gezeigten Daten als Pille wie in der Merkliste: Zeichen in der Tonfarbe (`StatusMark`) und der Satz,
/// z. B. «Alles aktuell · vor 2 Min.», «Offline · Stand 14:05», «Kurse veraltet · Stand 09:12».
/// Für Markt- und Portfolio-Tab (die Merkliste hat ihre eigene, antippbare Pille). VoiceOver: der Satz
/// mit « · » als Pause, oder `spoken`. Wie Android `DataStatusPill`.
struct DataStatusPill: View {
    let text: String
    let tone: DataStatusTone
    var spoken: String? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let color = tone.color
        HStack(spacing: 8) {
            StatusMark(tone: tone.mark, color: color)
            Text(text)
                .font(.caption.weight(.medium).monospacedDigit())
                .foregroundStyle(AppColors.onSurface)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, Spacing.sm)
        .background(color.opacity(0.12), in: Capsule())
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: tone)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: spoken ?? A11ySpeech.spokenDots(text)))
    }
}
