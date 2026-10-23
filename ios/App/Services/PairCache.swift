import Foundation

/// Gespeicherte Paarliste einer Börse samt Zeitpunkt des letzten Abgleichs (Gegenstück zu
/// `MarketPairsInfo.kt`). Für die Auswahl im Explorer braucht es Coins, Gegenwährungen, Kontrakte
/// und das passende Paar: Diese Nachschlagetabellen entstehen erst beim ersten Zugriff und dann nur
/// einmal je Liste. Alle Listen folgen der Reihenfolge, in der etwas in `pairs` zuerst auftaucht.
struct MarketPairsInfo: Codable, Sendable {
    var lastSyncDate: Int64
    var pairs: [MarketPair] {
        didSet { lookup = PairLookupHolder(pairs) }
    }

    /// Wird nicht gespeichert; kopierte Werte greifen auf dieselben (einmal gebauten) Tabellen zu.
    private var lookup: PairLookupHolder

    private enum CodingKeys: String, CodingKey {
        case lastSyncDate, pairs
    }

    init(lastSyncDate: Int64 = 0, pairs: [MarketPair] = []) {
        self.lastSyncDate = lastSyncDate
        self.pairs = pairs
        self.lookup = PairLookupHolder(pairs)
    }

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        self.init(lastSyncDate: try box.decode(Int64.self, forKey: .lastSyncDate),
                  pairs: try box.decode([MarketPair].self, forKey: .pairs))
    }

    func encode(to encoder: Encoder) throws {
        var box = encoder.container(keyedBy: CodingKeys.self)
        try box.encode(lastSyncDate, forKey: .lastSyncDate)
        try box.encode(pairs, forKey: .pairs)
    }

    var count: Int { pairs.count }

    /// Alle Coins (Basis), je einmal.
    var baseCurrencies: [String] { lookup.tables.coins }

    func quoteCurrencies(for base: String) -> [String] {
        lookup.tables.quotesOf[base] ?? []
    }

    func contractTypes(base: String?, quote: String?) -> [FuturesContractType] {
        guard let base, let quote else { return [] }
        return lookup.tables.contractsOf[PairLookup.Duo(base: base, quote: quote)] ?? []
    }

    /// Gegenwährungen für «Alle …-Paare»: die meistgenutzte vorne.
    var bulkQuoteCurrencies: [String] { lookup.tables.quotesByUse }

    /// Startwert der Sammelauswahl: USDT, falls vorhanden, sonst die meistgenutzte.
    var defaultBulkQuote: String? {
        let candidates = bulkQuoteCurrencies
        if let usdt = candidates.first(where: { $0.caseInsensitiveCompare("USDT") == .orderedSame }) { return usdt }
        return candidates.first
    }

    /// Paare mit dieser Gegenwährung (optional nur ein Kontrakttyp), doppelte weg, nach Coin sortiert.
    func pairs(withQuote quote: String, contractType: FuturesContractType? = nil) -> [MarketPair] {
        var taken = Set<String>()
        var picked: [MarketPair] = []
        for item in pairs where item.quote == quote {
            if let contractType, item.contractType != contractType { continue }
            guard taken.insert("\(item.base)|\(item.quote)|\(item.contractType.rawValue)").inserted else { continue }
            picked.append(item)
        }
        return picked.sorted { $0.base.caseInsensitiveCompare($1.base) == .orderedAscending }
    }

    /// Das zuerst gelistete Paar mit genau dieser Basis, Gegenwährung und Kontraktart.
    func pair(base: String, quote: String, contractType: FuturesContractType) -> MarketPair? {
        lookup.tables.firstPair[PairLookup.Trio(duo: PairLookup.Duo(base: base, quote: quote), contract: contractType)]
    }
}

/// Nachschlagetabellen über eine Paarliste (Reihenfolge = erstes Auftreten).
private struct PairLookup: Sendable {
    struct Duo: Hashable, Sendable {
        let base: String
        let quote: String
    }

    struct Trio: Hashable, Sendable {
        let duo: Duo
        let contract: FuturesContractType
    }

    private(set) var coins: [String] = []
    private(set) var quotesOf: [String: [String]] = [:]
    private(set) var contractsOf: [Duo: [FuturesContractType]] = [:]
    private(set) var firstPair: [Trio: MarketPair] = [:]
    private(set) var quotesByUse: [String] = []

    init(_ list: [MarketPair]) {
        var knownCoins = Set<String>()
        var knownDuos = Set<Duo>()
        var tally: [String: Int] = [:]
        for item in list {
            let duo = Duo(base: item.base, quote: item.quote)
            if !knownCoins.contains(item.base) {
                knownCoins.insert(item.base)
                coins.append(item.base)
            }
            if knownDuos.insert(duo).inserted {
                quotesOf[item.base, default: []].append(item.quote)
            }
            let trio = Trio(duo: duo, contract: item.contractType)
            if firstPair[trio] == nil {
                firstPair[trio] = item
                contractsOf[duo, default: []].append(item.contractType)
            }
            // Leere Gegenwährung zählt nicht für die Sammelauswahl
            if !item.quote.trimmingCharacters(in: .whitespaces).isEmpty {
                tally[item.quote, default: 0] += 1
            }
        }
        quotesByUse = tally.sorted(by: Self.moreUsedFirst).map(\.key)
    }

    /// Häufigere zuerst, bei Gleichstand alphabetisch.
    private static func moreUsedFirst(_ a: (key: String, value: Int), _ b: (key: String, value: Int)) -> Bool {
        a.value == b.value ? a.key < b.key : a.value > b.value
    }
}

/// Baut die Tabellen einer Liste beim ersten Lesen und hält sie fest; gesperrt für parallele Zugriffe.
private final class PairLookupHolder: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `guardLock` (NSLock)
    private let source: [MarketPair]
    private let guardLock = NSLock()
    private var ready: PairLookup?

    init(_ source: [MarketPair]) {
        self.source = source
    }

    var tables: PairLookup {
        guardLock.lock()
        defer { guardLock.unlock() }
        if ready == nil { ready = PairLookup(source) }
        return ready!
    }
}

/// Paarlisten aller Börsen: im Speicher und je Börse als JSON-Datei.
actor PairCache {
    static let shared = PairCache()

    /// Schon gelesene bzw. frisch geladene Listen.
    private var memory: [String: MarketPairsInfo] = [:]

    /// Börsen, deren Paarliste Kontrakte mit TradFi-Kennzeichen enthält.
    private static let marksTradFi: Set<String> = ["BinanceFutures", "BybitFutures", "OkexFutures", "MexcFutures", "BitgetFutures"]

    // MARK: Ablage

    private var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let target = base.appendingPathComponent("pairs", isDirectory: true)
        try? FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
        return target
    }

    private func fileURL(_ key: String) -> URL { folder.appendingPathComponent("\(key).json") }

    private func readFile(_ key: String) -> MarketPairsInfo? {
        guard let raw = try? Data(contentsOf: fileURL(key)) else { return nil }
        return try? JSONDecoder().decode(MarketPairsInfo.self, from: raw)
    }

    private func writeFile(_ info: MarketPairsInfo, key: String) {
        guard let raw = try? JSONEncoder().encode(info) else { return }
        try? raw.write(to: fileURL(key), options: .atomic)
    }

    // MARK: Lesen

    /// Liste der Börse (gespeichert, sonst die fest eingebauten Paare mit Datum 0). TradFi-Futures
    /// (Aktien, Rohstoffe, Devisen, Pre-IPO – `MarketPair.tradFi`) erscheinen nur, wenn
    /// Einstellungen › Erweitert › Token & TradFi sie erlaubt; die Datei behält immer alles, ein
    /// Umschalten greift also ohne neues Laden.
    func pairs(for key: String) -> MarketPairsInfo {
        visible(storedPairs(for: key))
    }

    /// Für die Paar-Auswahl: wie `pairs(for:)`; bei Binance mit geladener Spot-Liste kommen die
    /// Alpha-Token aus der schon gespeicherten Alpha-Liste dazu (kein Abruf), je als «SYMBOL/USDT»
    /// mit Kennung «ALPHA_<id>USDT». Ist das Kürzel schon als Spot-Paar vergeben, heisst das Token
    /// «SYMBOL_<Nummer>» (`BinanceAlpha.pairs`). Sichtbar werden sie erst über «inkl. Alpha-Tokens»
    /// (`scoped`). Gegenstück zu Android `getMarketCurrencyPairsInfo(withAlpha = true)`.
    func selectionPairs(for key: String) -> MarketPairsInfo {
        let base = pairs(for: key)
        guard key == BinanceAlpha.marketKey, base.lastSyncDate > 0 else { return base }
        let extra = BinanceAlpha.pairs(tokens: CoinLogoStore.alphaTokens, spotPairs: base.pairs)
        guard !extra.isEmpty else { return base }
        return MarketPairsInfo(lastSyncDate: base.lastSyncDate, pairs: (base.pairs + extra).sorted())
    }

    func hasSynced(_ key: String) -> Bool { storedPairs(for: key).lastSyncDate > 0 }

    /// Schlüssel (`CoinLogos.pairKey`) aller TradFi-Paare der gespeicherten Liste, unabhängig vom
    /// TradFi-Schalter; nil, wenn noch nie geladen.
    func tradFiPairKeys(for key: String) -> Set<String>? {
        let stored = storedPairs(for: key)
        guard stored.lastSyncDate > 0 else { return nil }
        var keys = Set<String>()
        for item in stored.pairs where item.isTradFi {
            keys.insert(CoinLogos.pairKey(marketKey: key, base: item.base, quote: item.quote,
                                          contractType: String(item.contractType.rawValue)))
        }
        return keys
    }

    // MARK: Laden

    /// Holt die Liste frisch von der Börse; eine nicht leere Liste wird gemerkt und gespeichert.
    @discardableResult
    func sync(_ key: String) async throws -> MarketPairsInfo {
        guard let market = MarketsConfig.market(key) else {
            throw UserFriendlyMarketError(message: L("market_unavailable_error"))
        }
        let fetched = try await MarketService.fetchCurrencyPairs(market: market)
        let fresh = MarketPairsInfo(lastSyncDate: TimeUtils.nowMillis, pairs: fetched)
        if !fetched.isEmpty {
            memory[key] = fresh
            writeFile(fresh, key: key)
        }
        return visible(fresh)
    }

    // MARK: Intern

    private func storedPairs(for key: String) -> MarketPairsInfo {
        if let known = memory[key] { return known }
        guard let fromDisk = readFile(key) else { return builtIn(key) }
        // Datei einer älteren Version ohne TradFi-Kontrakte: löschen; die leere Liste sorgt beim
        // nächsten Wählen der Börse für ein vollständiges neues Laden
        if Self.marksTradFi.contains(key), !fromDisk.pairs.isEmpty, !fromDisk.pairs.contains(where: { $0.tradFi != nil }) {
            try? FileManager.default.removeItem(at: fileURL(key))
            return MarketPairsInfo()
        }
        // Ältere Dateien kennen inverse Perpetuals noch nicht (`FuturesContractType.normalized`)
        let upgraded = Self.withInversePerpetuals(fromDisk, marketKey: key)
        memory[key] = upgraded
        return upgraded
    }

    /// Ohne Datei: die fest hinterlegten Paare der Börse (sortiert, Datum 0, nicht gemerkt).
    private func builtIn(_ key: String) -> MarketPairsInfo {
        guard let market = MarketsConfig.market(key), let table = market.currencyPairs else { return MarketPairsInfo() }
        var list: [MarketPair] = []
        for (base, quotes) in table {
            for quote in quotes { list.append(MarketPair(base, quote, nil)) }
        }
        return MarketPairsInfo(lastSyncDate: 0, pairs: list.sorted())
    }

    /// Kontrakttypen der gespeicherten Liste so, wie die heutige Paarliste sie liefert (inverse
    /// Perpetuals als `.inversePerpetual`); unverändert, wenn nichts anders wird. Wie Android.
    private static func withInversePerpetuals(_ info: MarketPairsInfo, marketKey: String) -> MarketPairsInfo {
        let target = info.pairs.map { FuturesContractType.normalized(marketKey: marketKey, pairId: $0.pairId, $0.contractType) }
        guard zip(info.pairs, target).contains(where: { $0.0.contractType != $0.1 }) else { return info }
        var result = info
        result.pairs = zip(info.pairs, target).map { item, type in
            var changed = item
            changed.contractType = type
            return changed
        }
        return result
    }

    /// TradFi-Paare fallen weg, solange «Token & TradFi» sie nicht zulässt. Krypto bleibt hier immer
    /// (Portfolio und Start-Auswahl brauchen es); «Nur TradFi» filtert erst die Auswahl (`scoped`).
    private func visible(_ info: MarketPairsInfo) -> MarketPairsInfo {
        guard info.pairs.contains(where: \.isTradFi) else { return info }
        if SharedStorage.loadSettings().assetScope.allowsTradFi { return info }
        return MarketPairsInfo(lastSyncDate: info.lastSyncDate, pairs: info.pairs.filter { !$0.isTradFi })
    }
}

extension MarketPairsInfo {
    /// Teilmenge der Paare von `marketKey`, die `scope` in der Auswahl zeigt; fällt nichts weg,
    /// kommt dieselbe Liste zurück. `hiddenAlpha` wie bei `AssetScope.allows`.
    func scoped(_ scope: AssetScope, marketKey: String = "", hiddenAlpha: Set<String> = [],
                pairNames: [String: String] = [:], alphaNames: [String: String] = [:]) -> MarketPairsInfo {
        func shows(_ item: MarketPair) -> Bool {
            scope.allows(item, marketKey: marketKey, hiddenAlpha: hiddenAlpha, pairNames: pairNames, alphaNames: alphaNames)
        }
        let kept = pairs.filter(shows)
        return kept.count == pairs.count ? self : MarketPairsInfo(lastSyncDate: lastSyncDate, pairs: kept)
    }
}
