import Foundation

/// Name und Sprachname des Platzhalters.
private let unknownLabel = "UNKNOWN"

/// Platzhalter für einen gespeicherten Börsenschlüssel, den diese App nicht kennt — etwa aus einer Sicherung, die
/// eine hier nicht vorhandene Börse nennt. Fragt nichts ab (leere Adresse) und bietet keine Paare an
/// (leere Tabelle), damit nichts Neues darauf angelegt wird. Wie `UnknownExchange.kt`.
final class UnknownExchange: Exchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(key: "UnknownExchange", name: unknownLabel, ttsName: unknownLabel, currencyPairs: [:])
    }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String { "" }
}
