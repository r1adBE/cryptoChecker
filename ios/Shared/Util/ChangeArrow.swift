import SwiftUI

/// Richtungspfeil zu einer Veränderung — gleiche Symbole und Regel wie die
/// Prozent-Pille der Merkliste: nur bei sichtbarer Veränderung (|x| ≥ 0.005 %),
/// Richtung nach dem Vorzeichen, nie nach «Farben tauschen». Für App und Widgets.
/// Grösse und Farbe setzt der Aufrufer (`.font`, `.foregroundStyle`).
struct ChangeArrowIcon: View {
    let change: Double?

    var body: some View {
        if let name = Self.symbolName(change) {
            Image(systemName: name)
                .accessibilityHidden(true)
        }
    }

    static func symbolName(_ change: Double?) -> String? {
        guard let change, change.isFinite, abs(change) >= 0.005 else { return nil }
        return change > 0 ? "arrow.up.right" : "arrow.down.right"
    }
}
