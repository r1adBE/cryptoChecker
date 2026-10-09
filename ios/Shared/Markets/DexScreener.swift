import Foundation

/// Ein DEX-Pool aus der Suche.
struct DexPool: Hashable, Sendable, Identifiable {
    let chainId: String
    let dexId: String
    let pairAddress: String
    let baseSymbol: String
    let quoteSymbol: String
    let priceUsd: Double?
    let liquidityUsd: Double?

    /// Kennung für die Kursabfrage: „chain/pairAddress“.
    var pairId: String { "\(chainId)/\(pairAddress)" }

    var id: String { pairId }

    /// Quote in der Watchlist: Kurse kommen in USD; die Chain hängt dran,
    /// damit gleichnamige Token auf verschiedenen Chains unterscheidbar sind.
    var watchQuote: String { "USD (\(chainId))" }
}

/// Kurse dezentraler Börsen (Uniswap, PancakeSwap, Raydium …) über DexScreener.
/// Kein Paar-Sync: Pools werden über die Suche gefunden und einzeln abgefragt.
/// Frei, ohne Schlüssel; Limit laut Anbieter rund 300 Abfragen pro Minute.
final class DexScreener: Market {
    private static let base = "https://api.dexscreener.com"

    init() {
        super.init(key: "DexScreener", name: "DexScreener", ttsName: "Dex Screener")
    }

    override func url(requestId: Int, info: CheckerInfo) -> String {
        "\(DexScreener.base)/latest/dex/pairs/\(info.pairId ?? "null")"
    }

    override func parseTicker(requestId: Int, json: JObject, ticker: inout Ticker, info: CheckerInfo) throws {
        let candidate = json.optArray("pairs").flatMap { $0.optObject(0) } ?? json.optObject("pair")
        guard let pair = candidate else { throw JSONError(message: "Pool not found") }

        let priceText = try pair.string("priceUsd")
        guard let price = Double(priceText) else { throw JSONError(message: "priceUsd ist keine Zahl: \(priceText)") }
        ticker.last = price
        if let volume = pair.optObject("volume") {
            ticker.volQuote = volume.optDouble("h24", Ticker.noData)
        }
        // priceChange.h24 = gleitende 24 h in Prozent
        ticker.change24hPercent = pair.optObject("priceChange").flatMap { Change24h.percent($0.optDouble("h24")) }
    }

    // MARK: Suche

    static func searchURL(_ query: String) -> String {
        "\(base)/latest/dex/search?q=" + query.trimmingCharacters(in: .whitespacesAndNewlines).urlQueryEncoded
    }

    /// Suchergebnis, nach Liquidität sortiert (meist der relevante Pool zuerst).
    static func parseSearch(_ response: String) throws -> [DexPool] {
        var result: [DexPool] = []
        if let pairs = try JObject(string: response).optArray("pairs") {
            for p in try pairs.allObjects() {
                guard let baseToken = p.optObject("baseToken") else { continue }
                guard let quoteToken = p.optObject("quoteToken") else { continue }
                let liquidity = p.optObject("liquidity")?.optDouble("usd")
                result.append(DexPool(
                    chainId: p.optString("chainId"),
                    dexId: p.optString("dexId"),
                    pairAddress: p.optString("pairAddress"),
                    baseSymbol: baseToken.optString("symbol"),
                    quoteSymbol: quoteToken.optString("symbol"),
                    priceUsd: Double(p.optString("priceUsd")),
                    liquidityUsd: (liquidity?.isNaN ?? true) ? nil : liquidity
                ))
            }
        }
        // Stabil absteigend nach Liquidität (wie sortedByDescending).
        return result
            .filter { !$0.chainId.isEmpty && !$0.pairAddress.isEmpty && !$0.baseSymbol.isEmpty && $0.priceUsd != nil }
            .enumerated()
            .sorted { a, b in
                let la = a.element.liquidityUsd ?? 0
                let lb = b.element.liquidityUsd ?? 0
                if la != lb { return la > lb }
                return a.offset < b.offset
            }
            .map { $0.element }
    }
}
