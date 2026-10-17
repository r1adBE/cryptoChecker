import Foundation

private let dexScreenerAPI = "https://api.dexscreener.com"

/// Ein DEX-Pool aus der Suche.
struct DexPool: Hashable, Sendable, Identifiable {
    let chainId: String
    let dexId: String
    let pairAddress: String
    let baseSymbol: String
    let quoteSymbol: String
    let priceUsd: Double?
    let liquidityUsd: Double?

    /// Kennung der Kursabfrage: «chain/pairAddress».
    var pairId: String { chainId + "/" + pairAddress }

    var id: String { pairId }

    /// Gegenwährung in der Merkliste: Kurse sind USD, die Chain steht dabei, damit gleichnamige Token verschiedener
    /// Chains auseinanderzuhalten sind.
    var watchQuote: String { "USD (" + chainId + ")" }
}

/// Kurse dezentraler Börsen (Uniswap, PancakeSwap, Raydium …) über DexScreener. Keine Paarliste (Pools findet die
/// Suche, `searchURL`), darum kein `SimpleMarket`; abgefragt wird jeder Pool einzeln. Frei und ohne Schlüssel; laut
/// Anbieter rund 300 Abfragen pro Minute.
final class DexScreener: Market {
    init() {
        super.init(key: "DexScreener", name: "DexScreener", ttsName: "Dex Screener")
    }

    // MARK: Einzelkurs

    override func tickerAddress(call: Int, info: PairQuery) -> String {
        dexScreenerAPI + "/latest/dex/pairs/" + (info.pairId ?? "null")
    }

    /// Nur der gemerkte Pool zählt: Liefert die API einen anderen zuerst (oder nur einen anderen), gilt das als
    /// «nicht gefunden» — nie die Kurse eines fremden Pools.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let found = DexScreener.poolsIn(json).first { pool in
            DexScreener.isPool(chainId: pool.optString("chainId"), pairAddress: pool.optString("pairAddress"),
                               pairId: info.pairId)
        }
        guard let pool = found else { throw JSONError(message: "Pool not found") }

        let priceText = try pool.string("priceUsd")
        guard let price = Double(priceText) else { throw JSONError(message: "priceUsd ist keine Zahl: \(priceText)") }
        ticker.last = price
        if let volume = pool.optObject("volume") { ticker.volQuote = volume.optDoubleOrMissing("h24") }
        // «priceChange.h24»: gleitende 24 h in Prozent
        ticker.change24hPercent = pool.optObject("priceChange").flatMap { Change24h.percent($0.optDouble("h24")) }
    }

    // MARK: Lesen

    /// Alle Pools der Antwort: zuerst das Array `pairs` (nur Objekte), dann ein einzelnes `pair`.
    private static func poolsIn(_ json: JObject) -> [JObject] {
        var found = json.optArray("pairs")?.optObjects() ?? []
        if let single = json.optObject("pair") { found.append(single) }
        return found
    }

    // MARK: Suche

    /// Gehört (`chainId`, `pairAddress`) zum gemerkten Pool `pairId` («chain/pairAddress»)? Die Adresse vergleicht
    /// ohne Gross-/Kleinschreibung (EVM-Prüfsummen). Wie `DexScreener.isPool` (Android).
    static func isPool(chainId: String, pairAddress: String, pairId: String?) -> Bool {
        let wanted = (pairId ?? "").trimmingCharacters(in: .whitespaces)
        guard let cut = wanted.firstIndex(of: "/") else { return false }
        let wantedChain = wanted[..<cut]
        let wantedAddress = wanted[wanted.index(after: cut)...]
        guard !wantedChain.isEmpty, !wantedAddress.isEmpty, !pairAddress.isEmpty else { return false }
        return chainId.caseInsensitiveCompare(wantedChain) == .orderedSame
            && pairAddress.caseInsensitiveCompare(wantedAddress) == .orderedSame
    }

    static func searchURL(_ query: String) -> String {
        dexScreenerAPI + "/latest/dex/search?q=" + query.trimmingCharacters(in: .whitespacesAndNewlines).urlQueryEncoded
    }

    /// Treffer der Suche, nach Liquidität absteigend (meist steht der relevante Pool vorn); gleich viel Liquidität
    /// behält die Reihenfolge der Antwort.
    static func parseSearch(_ body: String) throws -> [DexPool] {
        var pools: [DexPool] = []
        for entry in try JObject(string: body).optArray("pairs")?.allObjects() ?? [] {
            guard let base = entry.optObject("baseToken"), let quote = entry.optObject("quoteToken") else { continue }
            let liquidity = entry.optObject("liquidity")?.optDouble("usd")
            pools.append(DexPool(
                chainId: entry.optString("chainId"),
                dexId: entry.optString("dexId"),
                pairAddress: entry.optString("pairAddress"),
                baseSymbol: base.optString("symbol"),
                quoteSymbol: quote.optString("symbol"),
                priceUsd: Double(entry.optString("priceUsd")),
                liquidityUsd: liquidity.flatMap { $0.isNaN ? nil : $0 }
            ))
        }
        let usable = pools.filter { pool in
            !pool.chainId.isEmpty && !pool.pairAddress.isEmpty && !pool.baseSymbol.isEmpty && pool.priceUsd != nil
        }
        // Stabil sortieren: bei gleicher Liquidität entscheidet die Position in der Antwort
        let ranked = usable.indices.sorted { left, right in
            let leftLiquidity = usable[left].liquidityUsd ?? 0
            let rightLiquidity = usable[right].liquidityUsd ?? 0
            return leftLiquidity != rightLiquidity ? leftLiquidity > rightLiquidity : left < right
        }
        return ranked.map { usable[$0] }
    }
}
