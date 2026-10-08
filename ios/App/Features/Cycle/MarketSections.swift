import Foundation

/// Die drei Abschnitte des Markt-Tabs. «Jetzt» (`now`) bleibt immer offen; «Einordnung»
/// (`context`) und «Daten» (`data`) lassen sich zuklappen. Wie `MarketSection` (MarketSections.kt).
enum MarketSection: Hashable, CaseIterable {
    case now
    case context
    case data

    /// Lässt sich zuklappen (alles ausser «Jetzt»).
    var collapsible: Bool { self != .now }
}

/// Auf- und Zuklappen der Abschnitte im Markt-Tab (rein, testbar): «Einordnung» und «Daten»
/// beginnen immer zugeklappt, auch beim allerersten Besuch — oben steht nur «Jetzt», darunter je
/// eine Überschrift mit kurzer Zusammenfassung. Fachbegriffe erscheinen erst beim Aufklappen.
/// Was der Nutzer in dieser App-Sitzung gewählt hat, gilt beim erneuten Öffnen weiter.
/// Wie `MarketSections` (MarketSections.kt).
enum MarketSections {
    /// Trenner zwischen den Teilen einer Zusammenfassung («Gier 72 · Neutral»).
    static let summarySeparator = " · "

    /// Ob ein Abschnitt offen ist.
    /// - Parameter sessionChoice: zuletzt in dieser App-Sitzung gewählter Zustand; nil = nie getippt (zu)
    static func expanded(_ section: MarketSection, sessionChoice: Bool?) -> Bool {
        !section.collapsible || (sessionChoice ?? false)
    }

    /// Zusammenfassung für eine zugeklappte Überschrift: vorhandene, nicht leere Teile mit « · ».
    static func summary(_ parts: [String?]) -> String {
        parts.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: summarySeparator)
    }
}
