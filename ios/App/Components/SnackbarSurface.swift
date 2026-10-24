import SwiftUI

// Aussehen der kurzen Meldungen unten (Merkliste-Banner, Rückmeldung von «Paar hinzufügen», Toasts) — wie Android
// `AppSnackbarHost`.

extension SnackbarKind {
    /// SF Symbol vorne: Häkchen bei Erfolg, Ausrufezeichen bei Fehler; Info ohne Symbol.
    var symbol: String? {
        switch self {
        case .success: "checkmark.circle.fill"
        case .error: "exclamationmark.circle.fill"
        case .info: nil
        }
    }
}

/// Fläche passend zum Thema (`AppColors.containerHighest` — hell hell, dunkel dunkel, statt einer dunklen Pille in
/// beiden Modi). Erfolg und Fehler mit Rahmen 1.5 pt im Ton aus `SnackbarPalette`, Info nur mit dezenter Kontur
/// (`AppColors.outlineVariant`). Text darauf `AppColors.onSurface`, Aktion in der Akzentfarbe.
struct SnackbarSurface<S: InsettableShape>: ViewModifier {
    let kind: SnackbarKind
    let shape: S
    @Environment(\.priceColorScheme) private var scheme
    @Environment(\.priceHighContrast) private var highContrast

    func body(content: Content) -> some View {
        let tone = SnackbarPalette.tone(kind, scheme: scheme, highContrast: highContrast)
        content
            .background(AppColors.containerHighest, in: shape)
            .overlay(shape.strokeBorder(tone ?? AppColors.outlineVariant, lineWidth: tone == nil ? 1 : 1.5))
    }
}

/// Symbol vorne in der Meldung (`SnackbarKind.symbol`) im Ton der Art; dekorativ — VoiceOver liest den Meldungstext.
struct SnackbarKindIcon: View {
    let kind: SnackbarKind
    @Environment(\.priceColorScheme) private var scheme
    @Environment(\.priceHighContrast) private var highContrast

    var body: some View {
        if let symbol = kind.symbol, let tone = SnackbarPalette.tone(kind, scheme: scheme, highContrast: highContrast) {
            Image(systemName: symbol)
                .foregroundStyle(tone)
                .accessibilityHidden(true)
        }
    }
}

extension View {
    /// Fläche einer Meldung der Art `kind` (Karte mit `Radius.card`).
    func snackbarSurface(_ kind: SnackbarKind) -> some View {
        modifier(SnackbarSurface(kind: kind, shape: RoundedRectangle(cornerRadius: Radius.card, style: .continuous)))
    }

    /// Fläche einer Meldung der Art `kind` in der Form `shape` (z. B. Kapsel des Toasts).
    func snackbarSurface<S: InsettableShape>(_ kind: SnackbarKind, in shape: S) -> some View {
        modifier(SnackbarSurface(kind: kind, shape: shape))
    }
}
