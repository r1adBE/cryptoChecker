import Foundation

/// Die drei Bereiche des Markt-Tabs: «Jetzt» (`now`) steht im Tab selbst, «Einordnung» (`context`)
/// und «Daten» (`data`) sind eigene Unterseiten. Wie `MarketSection` (MarketSections.kt).
enum MarketSection: Int, Hashable, CaseIterable {
    case now
    case context
    case data

    /// Titel des Registers.
    var titleKey: String {
        switch self {
        case .now: "market_section_now"
        case .context: "market_section_context"
        case .data: "market_section_data"
        }
    }
}

/// Sprung von aussen an eine Stelle des Markt-Tabs (`AppRouter.marketJump`). Wie `MarketJump` (Android).
enum MarketJump: Equatable {
    /// Mitteilung «Wirtschaftstermine»: zum Wirtschaftsdaten-Hinweis («Jetzt» oder «Daten»).
    case macro
}

/// Register im Markt-Tab (rein, testbar): welcher Teil (`CycleRevealSlot`) in welchem Register
/// steht, wann unter einem Register noch die Ladezeile steht, wohin Wischen führt und in welchem
/// Register der Wirtschaftsdaten-Hinweis liegt. Das gewählte Register gilt für die App-Sitzung
/// (nicht gespeichert, beim Start immer «Jetzt»).
/// Wie `MarketSections` (MarketSections.kt).
enum MarketSections {
    /// Beim Start der App: «Jetzt».
    static let defaultSection: MarketSection = .now

    /// Register, in dem ein Teil steht (Überschriften-Teile zählen zu ihrem Register).
    static func of(_ slot: CycleRevealSlot) -> MarketSection {
        switch slot {
        case .pulse, .unusual: .now
        case .headerContext, .fearGreed, .phase, .dominance, .halving: .context
        case .headerData, .marketTotals, .gas, .coin: .data
        }
    }

    /// Letzter Teil eines Registers (Reihenfolge `CycleRevealSlot`).
    static func lastSlot(_ section: MarketSection) -> CycleRevealSlot {
        CycleRevealSlot.allCases.last { of($0) == section } ?? .coin
    }

    /// Ob unter dem Register noch die Ladezeile steht: sein letzter Teil ist noch nicht erschienen.
    static func loading(_ section: MarketSection, revealed: Set<CycleRevealSlot>) -> Bool {
        !revealed.contains(lastSlot(section))
    }

    /// Nachbar-Register; am Rand nil (kein Umlauf).
    static func neighbor(_ section: MarketSection, forward: Bool) -> MarketSection? {
        MarketSection(rawValue: section.rawValue + (forward ? 1 : -1))
    }

    /// Ziel beim waagrechten Wischen über `dx` (Punkte, nach rechts positiv): nach links wischen
    /// führt zum nächsten Register, nach rechts zum vorigen — bei Rechts-nach-links (`rtl`)
    /// umgekehrt (die Register stehen dann gespiegelt). Kürzer als `threshold` oder am Rand: nil.
    static func swipeTarget(_ section: MarketSection, dx: Double, threshold: Double, rtl: Bool) -> MarketSection? {
        guard abs(dx) >= threshold else { return nil }
        return neighbor(section, forward: (dx < 0) != rtl)
    }

    /// Unterseite aus dem Namen (wie die Route in Android): «Einordnung» oder «Daten»;
    /// Unbekanntes und «Jetzt» (steht im Tab selbst) ergeben «Einordnung».
    static func subpage(_ name: String?) -> MarketSection {
        // Vergleich statt `switch` über ein Optional (Muster "DATA" passt nicht sicher auf String?)
        name == "DATA" ? .data : .context
    }

    /// Register mit dem Wirtschaftsdaten-Hinweis: «Jetzt» bei einem Termin in ±2 h, sonst «Daten».
    static func macroSection(imminent: Bool) -> MarketSection {
        imminent ? .now : .data
    }
}
