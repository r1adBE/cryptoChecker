import SwiftUI

/// Sprungknopf der Merkliste («Zum Anfang» / «Zum Ende») — Regeln wie `WatchJump.kt`.
/// Der Knopf erscheint nur bei langen Listen, zeigt in der oberen Hälfte nach unten
/// (springt ans Ende), sonst nach oben (springt an den Anfang).
enum WatchlistJump {
    /// Erst ab mehr als so vielen Paaren in der sichtbaren Liste.
    static let minPairs = 30
    /// Weiter als so viele Zeilen: sofort springen statt lange zu animieren.
    static let instantDistance = 40
    /// Nach dem Scrollen so lange stehen lassen, dann ausblenden.
    static let hideDelayNanos: UInt64 = 2_000_000_000
    /// Durchmesser und Abstand zum Rand.
    static let buttonSize: CGFloat = 44
    static let margin: CGFloat = 16
    /// Ziele für `ScrollViewProxy.scrollTo`: Kopfzeile bzw. Abstand unter der letzten Zeile.
    static let topId = "watchlist-jump-top"
    static let endId = "watchlist-jump-end"

    /// Knopf möglich? Nicht im Sortiermodus; `pairs` = sichtbare (ggf. gefilterte) Paare.
    static func eligible(pairs: Int, sorting: Bool) -> Bool {
        !sorting && pairs > minPairs
    }

    /// Liegt die Mitte des sichtbaren Bereichs in der oberen Hälfte? Dann Pfeil nach unten.
    static func pointsDown(firstVisible: Int, lastVisible: Int, total: Int) -> Bool {
        guard total > 0 else { return true }
        return firstVisible + lastVisible < total - 1
    }

    /// Sanft scrollen nur über kurze Strecken und ohne «Bewegung reduzieren».
    static func animate(distance: Int, reduceMotion: Bool) -> Bool {
        !reduceMotion && abs(distance) <= instantDistance
    }
}

/// Gerade sichtbare Paare (per onAppear/onDisappear der Zeilen). Bewusst kein
/// beobachteter Zustand: Scrollen soll die Merkliste nicht bei jeder Zeile neu aufbauen.
final class WatchlistJumpTracker {
    var visible: Set<Int64> = []
}

/// Kleiner runder Knopf mit Pfeil: «Zum Ende» (nach unten) bzw. «Zum Anfang» (nach oben).
struct WatchlistJumpButton: View {
    let down: Bool
    let action: () -> Void

    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            Image(systemName: down ? "arrow.down" : "arrow.up")
                .scaledFont(size: 16, weight: .semibold, relativeTo: .body)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(accent.primary)
                .frame(width: WatchlistJump.buttonSize, height: WatchlistJump.buttonSize)
                .background(.regularMaterial, in: Circle())
                .overlay(Circle().strokeBorder(AppColors.outlineVariant.opacity(0.5), lineWidth: 0.5))
                .shadow(color: AppColors.shadow.opacity(0.15), radius: 6, y: 2)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L(down ? "watchlist_jump_end" : "watchlist_jump_start"))
    }
}

/// iOS 18: meldet, ob die Liste gerade gescrollt wird (auch bei ruhendem Finger).
/// Unter iOS 17 ohne Wirkung — dort zeigt das Kommen und Gehen der Zeilen das Scrollen an.
struct WatchlistScrollPhaseModifier: ViewModifier {
    let onChange: (Bool) -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in
                onChange(phase != .idle)
            }
        } else {
            content
        }
    }
}
