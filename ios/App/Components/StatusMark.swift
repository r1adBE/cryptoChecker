import SwiftUI

/// Zeichen vorne in einer Status-Pille: wie eine Ampel, aber auch ohne Farbe lesbar. Aktuell ✓, veraltet !,
/// Fehler ✕; neutral (offline, lädt, nichts gehandelt) bleibt der ruhige Punkt. Dieselben Zeichen wie im Bericht
/// «Letzte Aktualisierung». Nur Schmuck: der Satz daneben trägt die Aussage. Wie Android `StatusMark`.
struct StatusMark: View {
    let tone: StatusPillTone
    let color: Color

    var body: some View {
        if let symbol = Self.symbol(tone) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 14, height: 14)
                .accessibilityHidden(true)
        } else {
            Circle().fill(color).frame(width: 8, height: 8)
        }
    }

    static func symbol(_ tone: StatusPillTone) -> String? {
        switch tone {
        case .normal: "checkmark.circle.fill"
        case .warning: "exclamationmark.circle.fill"
        case .error: "xmark.circle.fill"
        case .neutral: nil
        }
    }
}

extension DataStatusTone {
    /// Zeichen zum Ton: veraltet ist eine Warnung (!) wie «x veraltet» in der Merkliste.
    var mark: StatusPillTone {
        switch self {
        case .fresh: .normal
        case .neutral: .neutral
        case .warn: .warning
        }
    }
}
