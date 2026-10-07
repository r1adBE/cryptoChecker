import Foundation

/// Paarliste einer Börse mit Datum der letzten Synchronisierung — wie `MarketPairsInfo.kt`.
/// Die Nachschlage-Verzeichnisse (Coins, Gegenwährungen, Kontrakte, Paare) werden je Liste
/// einmal beim ersten Zugriff aufgebaut, statt bei jeder Auswahl im Explorer die ganze
/// Liste zu durchsuchen. Reihenfolgen wie in `pairs` (erstes Vorkommen).
struct MarketPairsInfo: Codable, Sendable {
    var lastSyncDate: Int64 = 0
    var pairs: [CurrencyPairInfo] = [] {
        didSet { index = PairIndexBox(pairs) }
    }

    /// Nicht gespeichert; Kopien teilen sich das einmal aufgebaute Verzeichnis.
    private var index: PairIndexBox

    private enum CodingKeys: String, CodingKey {
        case lastSyncDate, pairs
    }

    init(lastSyncDate: Int64 = 0, pairs: [CurrencyPairInfo] = []) {
        self.lastSyncDate = lastSyncDate
        self.pairs = pairs
        self.index = PairIndexBox(pairs)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let syncDate = try container.decode(Int64.self, forKey: .lastSyncDate)
        let list = try container.decode([CurrencyPairInfo].self, forKey: .pairs)
        self.init(lastSyncDate: syncDate, pairs: list)
    }

    var count: Int { pairs.count }

    var baseCurrencies: [String] { index.value.bases }

    func quoteCurrencies(for base: String) -> [String] {
        index.value.quotesByBase[base] ?? []
    }

    func contractTypes(base: String?, quote: String?) -> [FuturesContractType] {
        guard let base, let quote else { return [] }
        return index.value.contractsByPair[PairIndex.QuoteKey(base: base, quote: quote)] ?? []
    }

    /// Gegenwährungen für «Alle …-Paare», die häufigste zuerst.
    var bulkQuoteCurrencies: [String] { index.value.bulkQuotes }

    /// Vorauswahl: USDT, sonst die häufigste Gegenwährung.
    var defaultBulkQuote: String? {
        let q = bulkQuoteCurrencies
        return q.first { $0.caseInsensitiveCompare("USDT") == .orderedSame } ?? q.first
    }

    func pairs(withQuote quote: String, contractType: FuturesContractType? = nil) -> [CurrencyPairInfo] {
        var seen = Set<String>()
        return pairs
            .filter { $0.quote == quote && (contractType == nil || $0.contractType == contractType) }
            .filter { seen.insert("\($0.base)|\($0.quote)|\($0.contractType.rawValue)").inserted }
            .sorted { $0.base.caseInsensitiveCompare($1.base) == .orderedAscending }
    }

    /// Erstes Paar mit genau dieser Basis, Gegenwährung und diesem Kontrakttyp.
    func pair(base: String, quote: String, contractType: FuturesContractType) -> CurrencyPairInfo? {
        index.value.pairByKey[PairIndex.ContractKey(base: base, quote: quote, contractType: contractType)]
    }
}

/// Einmal aufgebaute Verzeichnisse über eine Paarliste; Listen in der Reihenfolge des ersten Vorkommens.
private struct PairIndex: Sendable {
    struct QuoteKey: Hashable, Sendable {
        let base: String
        let quote: String
    }

    struct ContractKey: Hashable, Sendable {
        let base: String
        let quote: String
        let contractType: FuturesContractType
    }

    let bases: [String]
    let quotesByBase: [String: [String]]
    let contractsByPair: [QuoteKey: [FuturesContractType]]
    let pairByKey: [ContractKey: CurrencyPairInfo]
    let bulkQuotes: [String]

    init(_ pairs: [CurrencyPairInfo]) {
        var bases: [String] = []
        var seenBases = Set<String>()
        var quotes: [String: [String]] = [:]
        var seenQuotes = Set<QuoteKey>()
        var contracts: [QuoteKey: [FuturesContractType]] = [:]
        var byKey: [ContractKey: CurrencyPairInfo] = [:]
        var counts: [String: Int] = [:]
        for p in pairs {
            if seenBases.insert(p.base).inserted { bases.append(p.base) }
            let quoteKey = QuoteKey(base: p.base, quote: p.quote)
            if seenQuotes.insert(quoteKey).inserted { quotes[p.base, default: []].append(p.quote) }
            let contractKey = ContractKey(base: p.base, quote: p.quote, contractType: p.contractType)
            if byKey[contractKey] == nil {
                byKey[contractKey] = p
                contracts[quoteKey, default: []].append(p.contractType)
            }
            if !p.quote.trimmingCharacters(in: .whitespaces).isEmpty { counts[p.quote, default: 0] += 1 }
        }
        self.bases = bases
        self.quotesByBase = quotes
        self.contractsByPair = contracts
        self.pairByKey = byKey
        self.bulkQuotes = counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.map(\.key)
    }
}

/// Hält das Verzeichnis einer Paarliste und baut es beim ersten Zugriff (thread-sicher).
private final class PairIndexBox: @unchecked Sendable {
    private let pairs: [CurrencyPairInfo]
    private let lock = NSLock()
    private var built: PairIndex?

    init(_ pairs: [CurrencyPairInfo]) {
        self.pairs = pairs
    }

    var value: PairIndex {
        lock.lock()
        defer { lock.unlock() }
        if let built { return built }
        let made = PairIndex(pairs)
        built = made
        return made
    }
}

/// Zwischenspeicher der Paarlisten aller Börsen (eine Datei je Börse).
actor PairCache {
    static let shared = PairCache()

    private var memory: [String: MarketPairsInfo] = [:]

    private var directory: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pairs", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func file(_ key: String) -> URL { directory.appendingPathComponent("\(key).json") }

    /// Gespeicherte Liste, sonst die fest hinterlegten Paare der Börse (Datum 0).
    func pairs(for key: String) -> MarketPairsInfo {
        if let m = memory[key] { return m }
        if let data = try? Data(contentsOf: file(key)),
           let info = try? JSONDecoder().decode(MarketPairsInfo.self, from: data) {
            memory[key] = info
            return info
        }
        guard let market = MarketsConfig.market(key), let map = market.currencyPairs else { return MarketPairsInfo() }
        let pairs = map.flatMap { base, quotes in quotes.map { CurrencyPairInfo(base, $0, nil) } }.sorted()
        return MarketPairsInfo(lastSyncDate: 0, pairs: pairs)
    }

    func hasSynced(_ key: String) -> Bool { pairs(for: key).lastSyncDate > 0 }

    /// Lädt die Liste neu von der Börse und speichert sie.
    @discardableResult
    func sync(_ key: String) async throws -> MarketPairsInfo {
        guard let market = MarketsConfig.market(key) else { throw UserFriendlyMarketError(message: L("market_unavailable_error")) }
        let pairs = try await MarketService.fetchCurrencyPairs(market: market)
        let info = MarketPairsInfo(lastSyncDate: TimeUtils.nowMillis, pairs: pairs)
        if !pairs.isEmpty {
            memory[key] = info
            if let data = try? JSONEncoder().encode(info) { try? data.write(to: file(key), options: .atomic) }
        }
        return info
    }
}
