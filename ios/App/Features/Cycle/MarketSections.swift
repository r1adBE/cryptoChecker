import Foundation

/// Die drei Register des Markt-Tabs: «Jetzt» (`now`), «Einordnung» (`context`), «Daten» (`data`).
/// Immer ist genau eines sichtbar (nichts mehr zum Auf-/Zuklappen). Wie `MarketSection` (MarketSections.kt).
enum MarketSection: Int, Hashable, CaseIterable {
    case now
    case context
    case data

    /// Titel des Registers (dieselben Texte wie früher die Abschnitts-Überschriften).
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
/// (wie früher das Auf-/Zuklappen; nicht gespeichert, beim Start immer «Jetzt»).
/// Wie `MarketSections` (MarketSections.kt).
enum MarketSections {
    /// Beim Start der App: «Jetzt».
    static let defaultSection: MarketSection = .now

    /// Register, in dem ein Teil steht (die früheren Überschriften-Teile zählen zu ihrem Register).
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
    static func loading(_ section: MarketSection, revealed: Int) -> Bool {
        revealed <= lastSlot(section).rawValue
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

    /// Register mit dem Wirtschaftsdaten-Hinweis: «Jetzt» bei einem Termin in ±2 h, sonst «Daten».
    static func macroSection(imminent: Bool) -> MarketSection {
        imminent ? .now : .data
    }
}
