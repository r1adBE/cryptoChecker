import Foundation

/// Gemeinsamer Teil der Referenzkurse von Zentralbanken neben der EZB (`NbuReference`, `NbpReference`): Abschnitt
/// «Referenzkurs» unter «Devisen», keine Alarme, kein Handel. Paare = die Währungen, die diese Quelle liefert und keine
/// vorrangige (`ReferenceRates.assign`: EZB > NBU > NBP), je gegen USD und EUR, Kennung «AEDUSD». Einzel- und
/// Sammelkurs lesen dieselben verbundenen Antworten (`MultiResponseSource`). Keine Zeit der Börse. Wie Android
/// `BankReference.kt`.
protocol BankReference: MultiResponseSource {
    /// Welche Zentralbank; bestimmt Heimwährung, Takt und Quellzeile.
    var source: ReferenceSource { get }
    /// Adressen der Paarliste: zuerst die EZB (vorrangige Quelle), dann die Listen dieser und der übrigen Quellen.
    var pairListAddresses: [String] { get }
    /// Veröffentlichungen aus den Antworten von `responseAddresses`, aufsteigend; nil, wenn unlesbar.
    func publications(bodies: [String]) -> [ReferenceRates.Day]?
    /// Code-Mengen von EZB, NBU und NBP aus den Antworten von `pairListAddresses`.
    func listedCodes(bodies: [String]) throws -> (ecb: Set<String>, nbu: Set<String>, nbp: Set<String>)
}

extension BankReference {
    var category: ExchangeCategory { .forex }
    var allowsAlarms: Bool { false }
    var priceIsDailyReference: Bool { !source.weekly }

    // MARK: Einzelkurs (dieselben Antworten wie die Sammelabfrage)

    func tickerQueryError(call: Int, info: PairQuery) -> Error? {
        EcbReference.split(info.pairId) == nil ? JSONError(message: JSONError.noData) : nil
    }

    func tickerAddress(call: Int, info: PairQuery) -> String {
        EcbReference.split(info.pairId) == nil ? "" : responseAddresses(now: EpochTime.nowMillis).first ?? ""
    }

    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        guard let pair = EcbReference.split(info.pairId) else { throw JSONError(message: JSONError.noData) }
        try ReferenceRates.read(publications(bodies: MultiResponse.split(body)) ?? [], base: pair.base,
                                quote: pair.quote, into: &ticker)
    }

    /// Die Zentralbanken liefern keinen Fehlertext.
    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        try noErrorText()
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    func bulkTickersURL(call: Int) -> String? {
        call == 0 ? responseAddresses(now: EpochTime.nowMillis).first : nil
    }

    /// Alle Währungen der jüngsten Veröffentlichung (ohne `ReferenceRates.excluded`) gegen USD und EUR.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        guard let published = publications(bodies: MultiResponse.split(body)), let newest = published.last else {
            throw JSONError(message: JSONError.noData)
        }
        var tickers: [String: Ticker] = [:]
        let codes = newest.keys.filter { EcbRates.isCode($0) && !ReferenceRates.excluded.contains($0) }.sorted()
        for base in codes {
            for quote in ReferenceRates.quotes where quote != base {
                bulkRow(base + quote, tickers: &tickers, failed: &failed) {
                    try ReferenceRates.read(published, base: base, quote: quote, into: &$0)
                }
            }
        }
        return tickers
    }

    // MARK: Paarliste: alle Listen zusammen, damit jede Währung nur aus einer Quelle kommt

    var pairListCombined: Bool { true }
    var pairListCallCount: Int { pairListAddresses.count }

    func pairListAddress(call: Int) -> String? {
        pairListAddresses.indices.contains(call) ? pairListAddresses[call] : nil
    }

    func fillPairListCombined(bodies: [String]) throws -> [ExchangePair] {
        let codes = try listedCodes(bodies: bodies)
        guard !codes.ecb.isEmpty else { throw JSONError(message: JSONError.noData) }
        return ReferenceRates.pairs(ReferenceRates.assign(ecb: codes.ecb, nbu: codes.nbu, nbp: codes.nbp), source: source)
    }

    /// Codes des jüngsten EZB-Tags aus `eurofxref-daily.xml`; leer, wenn unlesbar.
    static func ecbCodes(_ body: String?) -> Set<String> {
        guard let body, let days = EcbRates.parseXML(body), let newest = days.keys.max(), let day = days[newest] else {
            return []
        }
        return Set(EcbRates.codes(day))
    }
}

/// Referenzkurse der Nationalbank der Ukraine (bank.gov.ua), täglich, für die Währungen, die die EZB nicht führt, je
/// gegen USD und EUR. Eine Abfrage über die letzten Tage (Kiewer Datum), gültig bis Mitternacht in Kiew. Wie Android.
struct NbuReference: BankReference {
    static let key = "NbuReference"
    let key: String = NbuReference.key
    let name: String = "NBU"
    let ttsName: String = "N B U"
    var source: ReferenceSource { .nbu }
    var pairListAddresses: [String] { [EcbRates.dailyURL, ReferenceRates.nbuListURL] }

    func responseAddresses(now: Int64) -> [String] {
        [ReferenceRates.nbuRangeURL(today: ReferenceRates.dateIn(now, ReferenceRates.kyiv))]
    }

    func validUntil(bodies: [String], now: Int64) -> Int64 {
        ReferenceRates.nbuValidUntil(bodies.first.flatMap(ReferenceRates.parseNbuRange), now: now)
    }

    func publications(bodies: [String]) -> [ReferenceRates.Day]? {
        bodies.first.flatMap(ReferenceRates.parseNbuRange).map(ReferenceRates.nbuPublications)
    }

    func listedCodes(bodies: [String]) throws -> (ecb: Set<String>, nbu: Set<String>, nbp: Set<String>) {
        guard bodies.count > 1, let nbu = ReferenceRates.nbuCodes(bodies[1]) else { throw JSONError(message: JSONError.noData) }
        return (Self.ecbCodes(bodies.first), nbu, [])
    }
}

/// Referenzkurse der Polnischen Nationalbank (api.nbp.pl), wöchentlich (Tabelle B, mittwochs), für alle übrigen
/// Währungen, je gegen USD und EUR; USD und EUR aus Tabelle A desselben Tags. Zwei Abfragen zusammen gelesen, gültig bis
/// zur nächsten Tabelle B. Wie Android.
struct NbpReference: BankReference {
    static let key = "NbpReference"
    let key: String = NbpReference.key
    let name: String = "NBP"
    let ttsName: String = "N B P"
    var source: ReferenceSource { .nbp }
    var pairListAddresses: [String] {
        [EcbRates.dailyURL, ReferenceRates.nbuListURL, ReferenceRates.nbpListBURL, ReferenceRates.nbpListAURL]
    }

    func responseAddresses(now: Int64) -> [String] { [ReferenceRates.nbpTableAURL, ReferenceRates.nbpTableBURL] }

    func validUntil(bodies: [String], now: Int64) -> Int64 {
        ReferenceRates.nbpValidUntil(tableA: bodies.first.flatMap(ReferenceRates.parseNbp),
                                     tableB: bodies.count > 1 ? ReferenceRates.parseNbp(bodies[1]) : nil, now: now)
    }

    func publications(bodies: [String]) -> [ReferenceRates.Day]? {
        guard bodies.count > 1, let tableB = ReferenceRates.parseNbp(bodies[1]) else { return nil }
        return ReferenceRates.nbpPublications(tableA: bodies.first.flatMap(ReferenceRates.parseNbp), tableB: tableB)
    }

    /// Codes der NBP = Tabelle B samt den Währungen der Tabelle A, die sonst niemand führt (CLP).
    func listedCodes(bodies: [String]) throws -> (ecb: Set<String>, nbu: Set<String>, nbp: Set<String>) {
        guard bodies.count > 2, let nbu = ReferenceRates.nbuCodes(bodies[1]),
              let tableB = ReferenceRates.parseNbp(bodies[2]) else { throw JSONError(message: JSONError.noData) }
        let tableA = bodies.count > 3 ? ReferenceRates.parseNbp(bodies[3]) ?? [:] : [:]
        let nbp = Set((Array(tableB.values) + Array(tableA.values)).flatMap { $0.keys })
        return (Self.ecbCodes(bodies.first), nbu, nbp)
    }
}
