import Foundation

/// Name und Sprachname des Platzhalters.
private let unknownLabel = "UNKNOWN"

/// Platzhalter für einen gespeicherten Börsenschlüssel, den diese App nicht kennt — etwa aus einer Sicherung, die
/// eine hier nicht vorhandene Börse nennt. Fragt nichts ab (leere Adresse) und bietet keine Paare an
/// (leere Tabelle), damit nichts Neues darauf angelegt wird. Wie `UnknownExchange.kt`.
struct UnknownExchange: Exchange {
    let key: String = "UnknownExchange"
    let name: String = unknownLabel
    let ttsName: String = unknownLabel
    let currencyPairs: CurrencyPairsMap? = [:]

    // MARK: Einzelkurs

    func tickerAddress(call: Int, info: PairQuery) -> String { "" }
}
