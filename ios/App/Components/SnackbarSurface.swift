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

/// Schliessen-✕ einer Meldung mit Aktion (das «OK»), damit niemand aus Reflex die Aktion (etwa «Rückgängig») antippt,
/// nur um die Meldung loszuwerden — wie Android `AppSnackbarVisuals.withDismissAction`. Tippfläche 44 × 44 pt; die
/// negativen Abstände lassen die Meldung dadurch nicht höher werden. VoiceOver «Schliessen».
struct SnackbarDismissButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .padding(.vertical, -Spacing.md)
        .padding(.trailing, -Spacing.sm)
        .accessibilityLabel(L("action_close"))
        .accessibilityIdentifier(UiTestTags.snackbarDismiss)
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
