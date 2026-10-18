import SwiftUI
import UIKit

/// Wechselt das App-Icon zur gewählten Akzentfarbe — nur nach einer Wahl in den
/// Einstellungen, nie automatisch (Hell/Dunkel: Varianten im Asset-Katalog).
enum AppIconSwitcher {
    @MainActor
    static func apply(accent: AccentColor) {
        guard UIApplication.shared.supportsAlternateIcons,
              UIApplication.shared.applicationState == .active else { return }
        let name = accent.iconName
        guard UIApplication.shared.alternateIconName != name else { return }
        UIApplication.shared.setAlternateIconName(name) { _ in }
    }
}
