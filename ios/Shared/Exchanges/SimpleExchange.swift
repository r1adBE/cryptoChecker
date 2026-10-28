import Foundation

/// The typical exchange: one fixed pair-list URL (GET) and one ticker URL `tickerURL` in which the pair id
/// replaces the placeholder `pairIdSlot`. `errorField`: field of an error response holding the exchange's text,
/// nil = none known (then override `errorMessage(call:json:info:)` or the exchange has none). A subclass still
/// implements `fillPairList` and `fillTicker` (plus the optional bulk hooks of `Exchange`).
///
/// Exchanges needing several pair lists, a combined list or POST requests subclass `Exchange` directly.
/// Android counterpart: `SimpleExchange.kt`.
class SimpleExchange: Exchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    private let pairListURL: String
    private let tickerURL: String
    private let errorField: String?

    init(key: String, name: String, pairListURL: String, tickerURL: String, ttsName: String? = nil,
         errorField: String? = nil, currencyPairs: CurrencyPairsMap? = nil) {
        self.pairListURL = pairListURL
        self.tickerURL = tickerURL
        self.errorField = errorField
        super.init(key: key, name: name, ttsName: ttsName, currencyPairs: currencyPairs)
    }

    // MARK: Einzelkurs

    /// Pair id inserted into `tickerURL`; default: the one from the pair list. Override where it can be derived.
    func pairId(_ info: PairQuery) -> String? { info.pairId }

    /// Ohne Kennung bleibt die Stelle leer (Android schreibt dort «null»); die Börse antwortet dann mit ihrem Fehler.
    override func tickerAddress(call: Int, info: PairQuery) -> String {
        tickerURL.replacingOccurrences(of: SimpleExchange.pairIdSlot, with: pairId(info) ?? "")
    }

    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        guard let errorField else { return try super.errorMessage(call: call, json: json, info: info) }
        // `string` wirft bei JSON null: kein Fehlertext «null» (wie Android `getText`)
        return try json.string(errorField)
    }

    // MARK: Paarliste

    final override func pairListAddress(call: Int) -> String? { pairListURL }

    /// Placeholder for the pair id in `tickerURL` (same as Android `PAIR_ID`).
    static let pairIdSlot = "%1$s"
}
