import Foundation

/// Placeholder for the pair id in `tickerURL` (same as Android `PAIR_ID`).
private let pairIdSlot = "%1$s"

/// The typical exchange: one fixed pair-list URL (GET) and one ticker URL `tickerURL` in which the pair id
/// replaces the placeholder «%1$s». `errorField`: field of an error response holding the exchange's text,
/// nil = none known (then implement `errorMessage(call:json:info:)` or the exchange has none). An adapter still
/// implements `fillPairList` and `fillTicker` (plus the optional bulk hooks of `Exchange`).
///
/// Exchanges needing several pair lists, a combined list or POST requests adopt `Exchange` directly.
/// Android counterpart: `SimpleExchange.kt`.
protocol SimpleExchange: Exchange {
    /// Fixed pair-list URL (GET), the only pair-list request (`pairListAddress`).
    var pairListURL: String { get }
    /// Ticker URL with the placeholder «%1$s» for the pair id.
    var tickerURL: String { get }
    /// Field of an error response holding the exchange's text; nil = none known. Default: nil.
    var errorField: String? { get }

    /// Pair id inserted into `tickerURL`; default: the one from the pair list. Implement where it can be derived.
    func pairId(_ info: PairQuery) -> String?
}

extension SimpleExchange {
    var errorField: String? { nil }

    func pairId(_ info: PairQuery) -> String? { info.pairId }

    // MARK: Einzelkurs

    func tickerAddress(call: Int, info: PairQuery) -> String { simpleTickerAddress(info) }

    /// `tickerURL` mit der Kennung (`pairId`). Ohne Kennung bleibt die Stelle leer (Android schreibt dort «null»);
    /// die Börse antwortet dann mit ihrem Fehler. Auch für Adapter, die nur einzelne Anfragen anders bilden.
    func simpleTickerAddress(_ info: PairQuery) -> String {
        tickerURL.replacingOccurrences(of: pairIdSlot, with: pairId(info) ?? "")
    }

    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try simpleErrorMessage(json)
    }

    /// Text im Feld `errorField`; ohne Feld keiner (`noErrorText()`). Auch für Adapter, die vorher selbst prüfen.
    func simpleErrorMessage(_ json: JObject) throws -> String? {
        guard let errorField else { try noErrorText() }
        // `string` wirft bei JSON null: kein Fehlertext «null» (wie Android `getText`)
        return try json.string(errorField)
    }

    // MARK: Paarliste

    func pairListAddress(call: Int) -> String? { pairListURL }
}
