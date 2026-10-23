import Foundation

/// Name und Sprachname des Platzhalters.
private let unknownLabel = "UNKNOWN"

/// Platzhalter für einen gespeicherten Börsenschlüssel, den diese Version nicht (mehr) kennt — etwa aus einer
/// Sicherung mit einer inzwischen entfernten Börse. Fragt nichts ab (leere Adresse) und bietet keine Paare an
/// (leere Tabelle), damit nichts Neues darauf angelegt wird. Wie `UnknownMarket.kt`.
final class UnknownMarket: Market, @unchecked Sendable { // @unchecked: wie `Market` (keine veränderlichen Eigenschaften)
    init() {
        super.init(key: "UnknownMarket", name: unknownLabel, ttsName: unknownLabel, currencyPairs: [:])
    }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String { "" }
}
