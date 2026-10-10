import Foundation

/// Ein Treffer der Suche über alle Börsen — wie `SearchHit` in `ExplorerPairSearch.kt`.
struct ExplorerSearchHit: Identifiable, Sendable {
    let market: Market
    let pair: CurrencyPairInfo

    var id: String { "\(market.key)|\(pair.base)|\(pair.quote)|\(pair.contractType.rawValue)" }
}

/// Fortschritt beim Nachladen der Paarlisten für die Suche.
struct ExplorerSearchProgress: Equatable, Sendable {
    let done: Int
    let total: Int
}

/// Sucht «BTC», «ETH USDT», «sol/usdc» oder wie an der Börse «BTCUSDT», «BTCUSDT Qtly 1225»,
/// «BTCUSD_PERP» in allen geladenen Paarlisten. Exakter Coin vor Wortanfang, gängige
/// Gegenwährungen zuerst, Spot vor Perpetual vor Laufzeit-Futures (Quartal …).
enum ExplorerPairSearch {
    private static let quoteRank = ["USDT", "USDC", "USD", "EUR", "CHF", "BTC"]

    /// Edelmetalle gibt es an Krypto-Börsen nur als goldgedeckte Token.
    /// «Gold» bzw. «Silber» (auch in anderen Sprachen) findet deshalb diese Coins.
    private static let gold = ["PAXG", "XAUT"]
    private static let silver = ["KAG", "XAG"]
    private static let aliases: [String: [String]] = {
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
        return Query(base: base, quote: words.count > 1 ? words[1] : nil, contracts: contracts, date: date)
    }

    /// Verfallsdatum als «yyMMdd»: aus der Kennung («BTCUSDT_261225»), sonst nach der Laufzeit berechnet.
    static func deliveryCode(_ pair: CurrencyPairInfo) -> String? {
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
    private static func contractRank(_ type: FuturesContractType) -> Int {
        switch type {
        case .none: return 0
        case .perpetual: return 1
        case .inversePerpetual: return 2
        default: return 3 + min(max(type.rawValue - FuturesContractType.weekly.rawValue, 0), 6)
        }
    }

    static func find(_ query: String, markets: [Market], cache: [String: MarketPairsInfo], limit: Int = 50) -> [ExplorerSearchHit] {
        guard let q = parse(query) else { return [] }
        let base = q.base
        let quote = q.quote
        let aliasBases = aliases[base] ?? []

        var hits: [(score: Int, hit: ExplorerSearchHit)] = []
        for market in markets {
            guard let info = cache[market.key] else { continue }
            for pair in info.pairs {
                let b = pair.base.uppercased()
                let c = pair.quote.uppercased()
                // «BTCUSDT» wie an der Börse: Coin und Gegenwährung zusammengeschrieben
                let joined = quote == nil && base == b + c
                let baseScore: Int
                if b == base || joined || aliasBases.contains(b) { baseScore = 0 } else if b.hasPrefix(base) { baseScore = 1 } else { continue }
                if let quote, !c.hasPrefix(quote) { continue }
                if let contracts = q.contracts, !contracts.contains(pair.contractType) { continue }
                if let date = q.date {
                    guard let code = deliveryCode(pair), code.hasSuffix(date) else { continue }
                }
                let qr = quoteRank.firstIndex(of: c) ?? quoteRank.count
                hits.append((baseScore * 1000 + qr * 10 + contractRank(pair.contractType), ExplorerSearchHit(market: market, pair: pair)))
            }
        }

        let sorted = hits.enumerated().sorted { l, r in
            let a = l.element, b = r.element
            if a.score != b.score { return a.score < b.score }
            if a.hit.pair.base != b.hit.pair.base { return a.hit.pair.base < b.hit.pair.base }
            let ma = a.hit.market.name.lowercased(), mb = b.hit.market.name.lowercased()
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
