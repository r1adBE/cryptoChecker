import Foundation

/// Name und Sprachname des Platzhalters.
private let unknownLabel = "UNKNOWN"

/// Platzhalter für einen gespeicherten Börsenschlüssel, den diese Version nicht (mehr) kennt — etwa aus einer
/// Sicherung mit einer inzwischen entfernten Börse. Fragt nichts ab (leere Adresse) und bietet keine Paare an
/// (leere Tabelle), damit nichts Neues darauf angelegt wird. Wie `UnknownExchange.kt`.
final class UnknownExchange: Exchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(key: "UnknownExchange", name: unknownLabel, ttsName: unknownLabel, currencyPairs: [:])
    }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String { "" }
}
