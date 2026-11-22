import SwiftUI

/// Beschriftung eines Knopfs, der etwas auf- bzw. zuklappt («Was bewegt sich mit?», «Details anzeigen»,
/// «Indikatoren einblenden», «Liste zeigen»): Text in der Themenfarbe mit Pfeil nach unten (zu)
/// bzw. oben (offen) — wie die Abschnitte «Einordnung»/«Daten». Kein «→»: das stünde für einen
/// Seitenwechsel. Wie `ExpandToggleButton` (Android).
struct ExpandToggleLabel: View {
    let title: String
    let expanded: Bool
    @Environment(\.appAccent) private var accent

    var body: some View {
        HStack(spacing: 4) {
            Text(title)
            Image(systemName: expanded ? "chevron.up" : "chevron.down")
                .font(.caption.weight(.bold))
                .accessibilityHidden(true)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(accent.primary)
        .padding(.vertical, Spacing.sm)
        .contentShape(Rectangle())
    }
}

extension View {
    /// Zustand eines Auf-/Zuklapp-Knopfs für VoiceOver («aufgeklappt»/«zugeklappt») — an den Knopf selbst, nicht an
    /// `ExpandToggleLabel`, damit der Knopf ihn ansagt. Wie Android `ExpandToggleButton` (stateDescription).
    func expandToggleState(_ expanded: Bool) -> some View {
        accessibilityValue(L(expanded ? "a11y_expanded" : "a11y_collapsed"))
    }
}
