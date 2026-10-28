import Foundation

/// Ein Treffer der Suche über alle Börsen — wie `SearchHit` in `ExplorerPairSearch.kt`.
struct ExplorerSearchHit: Identifiable, Sendable {
    let exchange: Exchange
    let pair: ExchangePair
    /// Alpha-Token («α», `AssetKind.ALPHA`).
    var alpha: Bool = false

    var id: String { "\(exchange.key)|\(pair.base)|\(pair.quote)|\(pair.contractType.rawValue)" }
}

/// Fortschritt beim Nachladen der Paarlisten für die Suche.
struct ExplorerSearchProgress: Equatable, Sendable {
    let done: Int
    let total: Int
}

/// Suche über Namen («bitcoin» → BTC, «ether» → ETH) und Börsen («kraken») — wie `NameMatch` in
/// `ExplorerPairSearch.kt`. Gross-/Kleinschreibung und Akzente egal.
enum ExplorerNameMatch {
    /// Rang «Name beginnt mit» (ganzer Name oder ein Wort darin).
    static let prefix = 0
    /// Rang «Name enthält».
    static let contains = 1

    /// Klein geschrieben, ohne Akzente («Pépé» → «pepe»), getrimmt.
    static func fold(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: .diacriticInsensitive, locale: nil)
            .lowercased()
    }

    /// Wörter eines (gefalteten) Namens: getrennt an allem, was weder Buchstabe noch Ziffer ist.
    static func words(_ folded: String) -> [Substring] {
        folded.split(whereSeparator: { !$0.isLetter && !$0.isNumber })
    }

    /// Trifft `query` den Namen? Beides schon `fold`. `prefix`: Name oder eines seiner Wörter beginnt
    /// damit (ab 2 Zeichen); `contains`: steht irgendwo darin (ab 3 Zeichen); sonst nil.
    static func score(_ foldedName: String, _ query: String) -> Int? {
        guard query.count >= 2, !foldedName.isEmpty else { return nil }
        if foldedName.hasPrefix(query) || words(foldedName).contains(where: { $0.hasPrefix(query) }) { return prefix }
        if query.count >= 3, foldedName.contains(query) { return contains }
        return nil
    }

    /// Nennt das Wort `word` (gefaltet) die Börse `foldedExchangeName`? Ab 3 Zeichen, Anfang eines
    /// Worts des Namens oder des Namens ohne Trennzeichen («cryptocom» → «Crypto.com»).
    static func matchesExchange(_ foldedExchangeName: String, _ word: String) -> Bool {
        guard word.count >= 3 else { return false }
        if words(foldedExchangeName).contains(where: { $0.hasPrefix(word) }) { return true }
        return String(foldedExchangeName.filter { $0.isLetter || $0.isNumber }).hasPrefix(word)
    }
}

/// Namen der Coins und TradFi-Paare, Schlüssel `CoinLogos.nameKey`, samt gefalteter Form
/// (`ExplorerNameMatch.fold`) — einmal je Namensliste gerechnet, nicht bei jedem Tastendruck. Die Coin-Suche
/// (`ExplorerCoinSearch`) geht zudem alle Einträge durch, um Coins zu finden, deren Paarliste noch nicht
/// geladen ist. Wie `NameIndex` in Android.
struct ExplorerNameIndex: Sendable {
    /// Ein Name: `key` wie `CoinLogos.nameKey`, `name` wie geliefert, `folded` für den Vergleich.
    struct Entry: Sendable {
        let key: String
        let name: String
        let folded: String
    }

    private let byKey: [String: Entry]

    static let empty = ExplorerNameIndex(names: [:])

    /// - Parameters:
    ///   - stockNames: Aktiennamen (Kürzel → Name) für die TradFi-Kürzel `tradFiBases` ohne eigenen
    ///     Namen — wie `CoinLogos.withStockNames` in Android.
    init(names: [String: String], stockNames: [String: String] = [:], tradFiBases: Set<String> = []) {
        var map: [String: Entry] = [:]
        map.reserveCapacity(names.count)
        func add(_ key: String, _ name: String) {
            let f = ExplorerNameMatch.fold(name)
            if !f.isEmpty { map[key] = Entry(key: key, name: name.trimmingCharacters(in: .whitespacesAndNewlines), folded: f) }
        }
        for (key, name) in names { add(key, name) }
        for base in tradFiBases {
            let key = CoinLogos.tradFiKey(base)
            guard names[key] == nil, let name = stockNames[base.trimmingCharacters(in: .whitespaces).uppercased()] else { continue }
            add(key, name)
        }
        byKey = map
    }

    var isEmpty: Bool { byKey.isEmpty }

    var entries: Dictionary<String, Entry>.Values { byKey.values }

    func folded(_ key: String) -> String? { byKey[key]?.folded }

    func name(_ key: String) -> String? { byKey[key]?.name }
}

/// Sucht «BTC», «ETH USDT», «sol/usdc» oder wie an der Börse «BTCUSDT», «BTCUSDT Qtly 1225»,
/// «BTCUSD_PERP» in allen geladenen Paarlisten, dazu über den Namen («bitcoin», «ether usdt»)
/// und die Börse («kraken», «btc kraken»). Rang: exakter Coin, Coin-Anfang, Name-Anfang, Name
/// enthält, nur Börse; darin gängige Gegenwährungen zuerst, Spot vor Perpetual vor
/// Laufzeit-Futures (Quartal …).
enum ExplorerPairSearch {
    private static let quoteRank = ["USDT", "USDC", "USD", "EUR", "CHF", "BTC"]

    /// Edelmetalle gibt es an Krypto-Börsen nur als goldgedeckte Token.
    /// «Gold» bzw. «Silber» (auch in anderen Sprachen) findet deshalb diese Coins.
    private static let gold = ["PAXG", "XAUT"]
    private static let silver = ["KAG", "XAG"]
    private static let aliasTable: [String: [String]] = {
        var map: [String: [String]] = [:]
        for word in ["GOLD", "ORO", "OURO", "GULD", "KULTA", "ZLATO", "ZŁOTO", "ARANY", "ALTIN", "EMAS", "VÀNG",
                     "ЗОЛОТО", "ΧΡΥΣΟΣ", "ΧΡΥΣΌΣ", "זהב", "ذهب", "طلا", "सोना", "ทองคำ", "金", "黄金", "골드", "금"] {
            map[word] = gold
        }
        for word in ["SILVER", "SILBER", "ARGENT", "ARGENTO", "PLATA", "PRATA", "ZILVER", "SØLV", "HOPEA", "STŘÍBRO", "SREBRO",
                     "EZÜST", "ARGINT", "GÜMÜŞ", "PERAK", "BẠC", "СЕРЕБРО", "СРІБЛО", "ΑΣΗΜΙ", "כסף", "فضة", "نقره", "चाँदी",
                     "เงิน", "銀", "银", "白银", "실버", "은"] {
            map[word] = silver
        }
        return map
    }()

    /// Coins, die das Wort `word` (gross) meint: «GOLD» → PAXG, XAUT; sonst keine.
    static func aliases(of word: String) -> [String] { aliasTable[word] ?? [] }

    /// Laufzeit-Wörter wie bei den Börsen («BTCUSDT Qtly 1225», «BTCUSD_PERP», «Quartal»).
    private static let perpetualWords: Set<String> = ["PERP", "PERPETUAL", "SWAP"]
    private static let quarterWords: Set<String> = ["QTLY", "QUARTERLY", "QUARTER", "QUARTAL", "1Q", "2Q", "CQ", "NQ"]

    /// Suchanfrage zerlegt: Coin, Gegenwährung, gewünschte Laufzeit und Verfallsdatum — wie Android.
    struct Query: Equatable {
        var base: String
        var quote: String?
        var contracts: Set<FuturesContractType>?
        /// «1225» (MMdd) oder «261225» (yyMMdd).
        var date: String?
        /// Alle Wörter ohne Laufzeit und Datum (Coin, Gegenwährung, Name, Börse).
        var words: [String] = []
    }

    static func parse(_ query: String) -> Query? {
        // Arabische/persische Ziffern wie 0–9 («BTC ١٢٢٥» = Verfallsdatum 1225), Richtungszeichen weg
        let tokens = ThresholdParser.latinDigits(query).uppercased()
            .split(whereSeparator: { " /-:_.".contains($0) })
            .map(String.init)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        var contracts: Set<FuturesContractType>?
        var date: String?
        var words: [String] = []
        for t in tokens {
            if perpetualWords.contains(t) {
                contracts = [.perpetual, .inversePerpetual]
            } else if quarterWords.contains(t) {
                switch t {
                case "1Q", "CQ": contracts = [.quarterly]
                case "2Q", "NQ": contracts = [.biquarterly]
                default: contracts = [.quarterly, .biquarterly]
                }
            } else if !words.isEmpty, t.count == 4 || t.count == 6, t.allSatisfy({ $0.isASCII && $0.isNumber }) {
                // Zahl nach dem Coin = Verfallsdatum (als erstes Wort bleibt es ein Coin, z. B. «1000»)
                date = t
            } else {
                words.append(t)
            }
        }
        guard let base = words.first else { return nil }
        return Query(base: base, quote: words.count > 1 ? words[1] : nil, contracts: contracts, date: date, words: words)
    }

    /// Verfallsdatum als «yyMMdd»: aus der Kennung («BTCUSDT_261225»), sonst nach der Laufzeit berechnet.
    static func deliveryCode(_ pair: ExchangePair) -> String? {
        if let id = pair.pairId, let range = id.range(of: "_", options: .backwards) {
            let suffix = id[range.upperBound...]
            if suffix.count == 6, suffix.allSatisfy({ $0.isASCII && $0.isNumber }) { return String(suffix) }
        }
        guard let date = pair.contractType.deliveryDate else { return nil }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month, .day], from: date)
        // POSIX: Kennung mit lateinischen Ziffern, unabhängig von der App-Sprache
        return String(format: "%02d%02d%02d", locale: Locale(identifier: "en_US_POSIX"),
                      (c.year ?? 0) % 100, c.month ?? 0, c.day ?? 0)
    }

    /// Reihenfolge der Kontrakte: Spot, Perpetual, dann die Laufzeiten (nächste zuerst).
    static func contractRank(_ type: FuturesContractType) -> Int {
        switch type {
        case .none: return 0
        case .perpetual: return 1
        case .inversePerpetual: return 2
        default: return 3 + min(max(type.rawValue - FuturesContractType.weekly.rawValue, 0), 6)
        }
    }

    /// Ränge (× 1000): Coin exakt, Coin-Anfang, Name-Anfang, Name enthält, nur die Börse.
    private static let nameTier = 2
    private static let exchangeTier = 4

    /// Coin- und Namensteil der Anfrage: erstes Wort Coin bzw. Name, zweites Gegenwährung; mehrere
    /// Wörter zusammen dürfen auch ein Name sein («shiba inu»). Merkt sich die Namens-Ränge je Coin.
    private final class Terms {
        let words: [String]
        private let names: ExplorerNameIndex
        private let base: String
        private let quote: String?
        private let aliasBases: [String]
        private let foldedBase: String
        private let foldedAll: String?
        private var memoAll: [String: Int] = [:]
        private var memoBase: [String: Int] = [:]

        init(_ words: [String], names: ExplorerNameIndex) {
            self.words = words
            self.names = names
            base = words.first ?? ""
            quote = words.count > 1 ? words[1] : nil
            aliasBases = ExplorerPairSearch.aliases(of: base)
            foldedBase = ExplorerNameMatch.fold(base)
            foldedAll = words.count > 1 ? ExplorerNameMatch.fold(words.joined(separator: " ")) : nil
        }

        /// Rang (0–3) für Coin `b` / Gegenwährung `c` (beide gross); nil = kein Treffer.
        func tier(_ b: String, _ c: String, tradFi: Bool, withNames: Bool) -> Int? {
            // «BTCUSDT» wie an der Börse: Coin und Gegenwährung zusammengeschrieben
            let joined = quote == nil && base == b + c
            var symbol: Int?
            if b == base || joined || aliasBases.contains(b) { symbol = 0 } else if b.hasPrefix(base) { symbol = 1 }
            if let symbol {
                if let quote, !c.hasPrefix(quote) { return nil }
                return symbol
            }
            guard withNames, !names.isEmpty else { return nil }
            let memoKey = tradFi ? "\u{1}" + b : b
            if let foldedAll, let t = nameTier(&memoAll, memoKey, b, tradFi, foldedAll) { return t }
            if let quote, !c.hasPrefix(quote) { return nil }
            return nameTier(&memoBase, memoKey, b, tradFi, foldedBase)
        }

        private func nameTier(_ memo: inout [String: Int], _ memoKey: String, _ b: String, _ tradFi: Bool,
                              _ q: String) -> Int? {
            if let cached = memo[memoKey] { return cached >= 0 ? cached : nil }
            let name = names.folded(CoinLogos.nameKey(b, tradFi: tradFi))
            let score = name.flatMap { ExplorerNameMatch.score($0, q) }
            let tier = score.map { ExplorerPairSearch.nameTier + $0 } ?? -1
            memo[memoKey] = tier
            return tier >= 0 ? tier : nil
        }
    }

    /// - Parameter scope: «Token & TradFi» — nur Paare, die die Auswahl zeigt.
    /// - Parameter names: Namen der Coins und TradFi-Paare für die Suche über den Namen.
    /// - Parameter hiddenAlpha: «inkl. Alpha-Tokens» aus: diese Alpha-Kürzel nicht zeigen (`AssetScope.allows`).
    /// - Parameter alphaSymbols: alle Alpha-Kürzel: Treffer mit «α» kennzeichnen (`AssetKind.of`).
    /// - Parameter alphaNames: Namen der Alpha-Token je Kürzel (`AssetKind.of`).
    /// - Parameter pairNames: Namen der Coins je Börse (Börsen-Schlüssel → Kürzel gross → Name; `AssetKind.of`).
    static func find(_ query: String, exchanges: [Exchange], cache: [String: ExchangePairsInfo], limit: Int = 50,
                     scope: AssetScope = .BOTH, names: ExplorerNameIndex = .empty,
                     hiddenAlpha: Set<String> = [], alphaSymbols: Set<String> = [],
                     alphaNames: [String: String] = [:],
                     pairNames: [String: [String: String]] = [:]) -> [ExplorerSearchHit] {
        guard let q = parse(query) else { return [] }
        let primary = Terms(q.words, names: names)
        let foldedWords = q.words.map(ExplorerNameMatch.fold)

        var hits: [(score: Int, hit: ExplorerSearchHit)] = []
        for exchange in exchanges {
            guard let info = cache[exchange.key] else { continue }
            // Keine Namen für DEX-Pools: dort darf ein fremder Token «BTC» heissen
            let withNames = CoinLogos.allowed(forExchange: exchange.key)
            // Wörter, die diese Börse nennen («btc kraken»): ohne sie wird der Rest gesucht
            let exchangeName = ExplorerNameMatch.fold(exchange.name)
            let exchangeWords = Set(foldedWords.indices.filter { ExplorerNameMatch.matchesExchange(exchangeName, foldedWords[$0]) })
            var rest: Terms?
            if !exchangeWords.isEmpty {
                let restWords = q.words.enumerated().filter { !exchangeWords.contains($0.offset) }.map(\.element)
                rest = Terms(restWords, names: names)
            }
            let exchangeNames = pairNames[exchange.key] ?? [:]
            for pair in info.pairs where scope.allows(pair, exchangeKey: exchange.key, hiddenAlpha: hiddenAlpha,
                                                      pairNames: exchangeNames, alphaNames: alphaNames) {
                let b = pair.base.uppercased()
                let c = pair.quote.uppercased()
                let tradFi = pair.tradFi == true
                var tier: Int? = primary.tier(b, c, tradFi: tradFi, withNames: withNames)
                if tier == nil, let rest {
                    tier = rest.words.isEmpty ? exchangeTier : rest.tier(b, c, tradFi: tradFi, withNames: withNames)
                }
                guard let tier else { continue }
                if let contracts = q.contracts, !contracts.contains(pair.contractType) { continue }
                if let date = q.date {
                    guard let code = deliveryCode(pair), code.hasSuffix(date) else { continue }
                }
                let qr = quoteRank.firstIndex(of: c) ?? quoteRank.count
                let kind = AssetKind.of(exchangeKey: exchange.key, base: pair.base, tradFi: tradFi, alphaSymbols: alphaSymbols,
                                        pairId: pair.pairId, pairName: exchangeNames[TokenIdentity.key(pair.base)],
                                        alphaNames: alphaNames)
                let hit = ExplorerSearchHit(exchange: exchange, pair: pair, alpha: kind == .ALPHA)
                hits.append((tier * 1000 + qr * 10 + contractRank(pair.contractType), hit))
            }
        }

        let sorted = hits.enumerated().sorted { l, r in
            let a = l.element, b = r.element
            if a.score != b.score { return a.score < b.score }
            if a.hit.pair.base != b.hit.pair.base { return a.hit.pair.base < b.hit.pair.base }
            let ma = a.hit.exchange.name.lowercased(), mb = b.hit.exchange.name.lowercased()
            if ma != mb { return ma < mb }
            return l.offset < r.offset
        }

        // Manche Börsen führen ein Paar doppelt — Listen-Schlüssel müssen eindeutig sein.
        var seen = Set<String>()
        var result: [ExplorerSearchHit] = []
        for entry in sorted where seen.insert(entry.element.hit.id).inserted {
            result.append(entry.element.hit)
            if result.count >= limit { break }
        }
        return result
    }
}
