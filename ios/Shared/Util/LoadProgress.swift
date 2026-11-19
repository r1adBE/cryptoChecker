import Foundation

/// Fortschritt eines Ladevorgangs aus mehreren gleichzeitigen Schritten (Merkliste: je Börse; Markt: je Karte;
/// Portfolio: Adressen, Kurse, Verlauf, Devisen …). Jeder fertige Schritt rückt den Balken vor, egal in welcher
/// Reihenfolge. Wie Android `LoadProgress`.
struct LoadProgress: Sendable {
    /// Kreisel beim Ziehen: nur so lange als «verstanden», danach übernimmt der Balken.
    static let pullAckMillis: UInt64 = 1_000
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

/// Fortschritt aus einer Menge laufender Teile (Markt: Bereiche, die gerade laden): Jeder Teil, der während eines
/// Ladens dazukommt, zählt mit; fertig ist, wer nicht mehr läuft. Läuft nichts mehr, beginnt die Zählung neu.
/// Wie Android `LoadTally`.
struct LoadTally<Key: Hashable> {
    private var seen: Set<Key> = []

    /// Anteil für den Balken; nil = nichts lädt.
    mutating func next(_ running: Set<Key>) -> Double? {
        guard !running.isEmpty else {
            seen = []
            return nil
        }
        seen.formUnion(running)
        return LoadProgress.shown(done: seen.count - running.count, total: seen.count)
    }
}
