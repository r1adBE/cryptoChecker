import Foundation

/// Ein Treffer der Suche über alle Börsen — wie `SearchHit` in `PairSearch.kt`.
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

/// Sucht «BTC», «ETH USDT» oder «sol/usdc» in allen geladenen Paarlisten.
/// Exakter Coin vor Wortanfang, gängige Gegenwährungen zuerst, Spot vor Futures.
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

    static func find(_ query: String, markets: [Market], cache: [String: MarketPairsInfo], limit: Int = 50) -> [ExplorerSearchHit] {
        let parts = query.uppercased()
            .split(whereSeparator: { $0 == " " || $0 == "/" || $0 == "-" || $0 == ":" })
            .map(String.init)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard let base = parts.first else { return [] }
        let quote = parts.count > 1 ? parts[1] : nil
        let aliasBases = aliases[base] ?? []

        var hits: [(score: Int, hit: ExplorerSearchHit)] = []
        for market in markets {
            guard let info = cache[market.key] else { continue }
            for pair in info.pairs {
                let b = pair.base.uppercased()
                let c = pair.quote.uppercased()
                let baseScore: Int
                if b == base || aliasBases.contains(b) { baseScore = 0 } else if b.hasPrefix(base) { baseScore = 1 } else { continue }
                if let quote, !c.hasPrefix(quote) { continue }
                let qr = quoteRank.firstIndex(of: c) ?? quoteRank.count
                let futures = pair.contractType == .none ? 0 : 1
                hits.append((baseScore * 1000 + qr * 10 + futures, ExplorerSearchHit(market: market, pair: pair)))
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
