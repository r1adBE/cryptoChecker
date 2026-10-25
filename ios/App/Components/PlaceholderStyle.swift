import SwiftUI
import UIKit

// Platzhalter der Eingabefelder in onSurfaceVariant (#474747 / #C6C6C6, ≥ 7:1 auf allen Flächen) — wie Android
// (Material-TextField: Platzhalter in onSurfaceVariant). Der System-Platzhalter (`placeholderText`) hielt nur
// 1.7:1 (hell) bzw. 2.4:1 (dunkel). Bei hohem Kontrast wie alle Nebentexte onSurface (`AppColors.onSurfaceVariant`).

extension Text {
    /// Platzhalter eines Eingabefelds (`prompt:`) in onSurfaceVariant.
    static func placeholder(_ text: String) -> Text {
        Text(text).foregroundStyle(AppColors.onSurfaceVariant)
    }
}

extension TextField where Label == Text {
    /// Eingabefeld mit Platzhalter in onSurfaceVariant; der Platzhalter ist zugleich die Beschriftung für VoiceOver
    /// (wie `TextField(_:text:)`).
    init(placeholder: String, text: Binding<String>) {
        self.init(text: text, prompt: Text.placeholder(placeholder)) { Text(placeholder) }
    }

    /// Wie `init(placeholder:text:)`, mitwachsend in Richtung `axis` (z. B. `.vertical` für mehrzeilige Notizen).
    init(placeholder: String, text: Binding<String>, axis: Axis) {
        self.init(text: text, prompt: Text.placeholder(placeholder), axis: axis) { Text(placeholder) }
    }
}

extension SecureField where Label == Text {
    /// Passwortfeld mit Platzhalter in onSurfaceVariant (wie `TextField(placeholder:text:)`).
    init(placeholder: String, text: Binding<String>) {
        self.init(text: text, prompt: Text.placeholder(placeholder)) { Text(placeholder) }
    }
}

/// Suchfelder von `.searchable` (UIKit `UISearchTextField`): SwiftUI übernimmt dort keine Farbe des `prompt`.
/// Der Platzhalter ist ein `UILabel` im Suchfeld — seine Farbe kommt einmal beim Start über `appearance`.
enum SearchFieldPlaceholder {
    @MainActor
    static func apply() {
        UILabel.appearance(whenContainedInInstancesOf: [UISearchTextField.self]).textColor =
            .contrastDynamic(ColorTokens.onSurfaceVariant, high: ColorTokens.onSurface)
    }
}
