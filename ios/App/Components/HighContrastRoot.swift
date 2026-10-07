import Combine
import SwiftUI
import UIKit

/// Wurzel der App für «Hoher Kontrast»: wirksam, wenn die Einstellung an ist
/// oder das System mehr Kontrast verlangt (`colorSchemeContrast == .increased`).
///
/// - Kursfarben: `\.priceHighContrast` für alle Ansichten darunter.
/// - Nebentexte/Linien: Die Einstellung setzt bei den Fenster-Szenen den Trait
///   `accessibilityContrast = .high` — so lösen `AppColors.onSurfaceVariant`,
///   `outline` usw. und die Systemfarben (`.secondary`, `secondaryLabel`) überall
///   ihre kontrastreiche Fassung auf, wie bei «Kontrast erhöhen» des Systems.
@MainActor
struct HighContrastRoot: ViewModifier {
    let setting: Bool
    @Environment(\.colorSchemeContrast) private var contrast

    func body(content: Content) -> some View {
        content
            .environment(\.priceHighContrast, setting || contrast == .increased)
            // Alle verbundenen Szenen: beim Start, bei jeder Änderung und für Szenen, die
            // später dazukommen (weiteres Fenster auf dem iPad, wiederhergestellte Szene).
            .onChange(of: setting, initial: true) { _, on in
                HighContrastScenes.apply(on)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScene.willConnectNotification)) { note in
                HighContrastScenes.apply(setting, extra: note.object as? UIWindowScene)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScene.didActivateNotification)) { note in
                HighContrastScenes.apply(setting, extra: note.object as? UIWindowScene)
            }
    }
}

extension View {
    func highContrastRoot(_ setting: Bool) -> some View {
        modifier(HighContrastRoot(setting: setting))
    }
}

/// Setzt bzw. entfernt den Kontrast-Trait aller verbundenen Fenster-Szenen.
@MainActor
enum HighContrastScenes {
    /// `extra`: eine Szene, die gerade verbunden wird (ist evtl. noch nicht in `connectedScenes`).
    static func apply(_ on: Bool, extra: UIWindowScene? = nil) {
        var scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let extra, !scenes.contains(extra) { scenes.append(extra) }
        for windowScene in scenes {
            if on {
                windowScene.traitOverrides.accessibilityContrast = .high
            } else if windowScene.traitOverrides.contains(UITraitAccessibilityContrast.self) {
                // Entfernen statt `.unspecified`: dann gilt wieder die Systemeinstellung
                windowScene.traitOverrides.remove(UITraitAccessibilityContrast.self)
            }
        }
    }
}
