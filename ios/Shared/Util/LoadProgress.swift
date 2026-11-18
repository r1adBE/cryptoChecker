import Foundation

/// Fortschritt eines Ladevorgangs aus mehreren gleichzeitigen Schritten (Portfolio: Adressen, Kurse, Verlauf,
/// Devisen …). Jeder fertige Schritt rückt den Balken vor, egal in welcher Reihenfolge. Wie Android `LoadProgress`.
struct LoadProgress: Sendable {
    /// Erst nach dieser Zeit erscheint der Balken: Schnelles Laden bleibt ohne Flackern.
    static let showAfterMillis: UInt64 = 500
    /// Ein kleiner Anfang, damit der Balken sofort lebt (nie ganz leer).
    static let start = 0.08

    let total: Int
    private(set) var done = 0

    init(total: Int) { self.total = total }

    /// Ein Schritt fertig; der angezeigte Anteil danach.
    mutating func step() -> Double {
        done = min(done + 1, total)
        return current
    }

    /// Angezeigter Anteil jetzt.
    var current: Double { Self.shown(done: done, total: total) }

    static func shown(done: Int, total: Int) -> Double {
        guard total > 0 else { return 1 }
        return start + (1 - start) * Double(min(max(done, 0), total)) / Double(total)
    }
}
