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
    /// Vertragsadresse des Basis-Tokens («baseToken.address»); leer, wenn die Antwort keine nennt.
    var baseAddress: String = ""

    /// Kennung der Kursabfrage: «chain/pairAddress».
    var pairId: String { chainId + "/" + pairAddress }

    var id: String { pairId }

    /// Gegenwährung in der Merkliste: Kurse sind USD, die Chain steht dabei, damit gleichnamige Token verschiedener
    /// Chains auseinanderzuhalten sind.
    var watchQuote: String { "USD (" + chainId + ")" }

    /// Gekürzte Vertragsadresse des Tokens («0x1234…abcd») für die Trefferzeile: Gleichnamige Token (zweimal «PEPE»)
    /// sind sonst nur über Liquidität und Preis zu unterscheiden. Leer ohne Adresse.
    var shortBaseAddress: String { DexScreener.shortAddress(baseAddress) }

    /// Genug Liquidität für Alarme (`DexScreener.alarmCapable`)? Sonst Hinweis «Geringe Liquidität – kein Alarm».
    var alarmCapable: Bool { DexScreener.alarmCapable(liquidityUsd) }
}

/// Kurse dezentraler Börsen (Uniswap, PancakeSwap, Raydium …) über DexScreener. Keine Paarliste (Pools findet die
/// Suche, `searchURL`), darum kein `SimpleMarket`. Mehrere Pools derselben Chain kommen gesammelt
/// (`/latest/dex/pairs/{chain}/{a,b,…}`, höchstens `bulkMaxPools` je Anfrage), einzelne über dieselbe Adresse. Frei
/// und ohne Schlüssel; laut Anbieter rund 300 Abfragen pro Minute.
final class DexScreener: Market {
    /// Schlüssel der Börse (Sicherung, Merkliste).
    static let key = "DexScreener"
    /// Lesefehler «Pool nicht gefunden» (Einzelkurs, ungültige Kennung).
    static let poolNotFound = "Pool not found"

    init() {
        super.init(key: DexScreener.key, name: "DexScreener", ttsName: "Dex Screener")
    }

    // MARK: Einzelkurs

    /// Kennung, die nicht unkodiert in den Pfad darf (`isSafePairId`): «nicht gefunden» ohne Anfrage
    /// (Android wirft in `tickerAddress`).
    override func tickerQueryError(call: Int, info: PairQuery) -> Error? {
        guard let pairId = info.pairId, DexScreener.isSafePairId(pairId) else {
            return JSONError(message: DexScreener.poolNotFound)
        }
        return nil
    }

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
        guard let pool = found else { throw JSONError(message: DexScreener.poolNotFound) }
        try DexScreener.copyPool(pool, into: &ticker)
    }

    // MARK: Sammelabfrage: Pools derselben Chain gemeinsam

    override var bulkTickersNumOfRequests: Int { 1 }

    // Ohne Auswahl gibt es keine Sammelabfrage (keine ungefilterte Adresse); ein fehlender Pool geht in die
    // Einzelabfrage, darum nicht «vollständig»
    override func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        let groups = DexScreener.bulkGroups(pairIds)
        return groups.indices.contains(call) ? DexScreener.bulkURL(groups[call]) : nil
    }

    override func bulkTickersRequestCount(pairIds: [String]) -> Int { DexScreener.bulkGroups(pairIds).count }

    /// Jeder Pool der Antwort unter «chain/pairAddress», so wie die Suche die Kennung bildet (`DexPool.pairId`); mit
    /// eigener Liquidität, die Alarm-Sperre unter `minAlarmLiquidityUsd` gilt je Pool. Pools ohne Preis werden
    /// übersprungen (dann Einzelabfrage).
    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for pool in DexScreener.poolsIn(try JObject(string: body)) {
            let chain = pool.optString("chainId")
            let address = pool.optString("pairAddress")
            if chain.isEmpty || address.isEmpty { continue }
            bulkRowIfActive(chain + "/" + address, row: pool, priceField: "priceUsd", tickers: &tickers,
                            failed: &failed) { try DexScreener.copyPool(pool, into: &$0) }
        }
        return tickers
    }

    // MARK: Lesen

    /// Kurs, Volumen, 24-h-Veränderung und Liquidität eines Pools (Einzel- und Sammelabfrage gleich).
    private static func copyPool(_ pool: JObject, into ticker: inout Ticker) throws {
        let priceText = try pool.string("priceUsd")
        guard let price = Double(priceText) else { throw JSONError(message: "priceUsd ist keine Zahl: \(priceText)") }
        ticker.last = price
        if let volume = pool.optObject("volume") { ticker.volQuote = volume.optDoubleOrMissing("h24") }
        // «priceChange.h24»: gleitende 24 h in Prozent
        ticker.change24hPercent = pool.optObject("priceChange").flatMap { Change24h.percent($0.optDouble("h24")) }
        // Liquidität bei jeder Abfrage: Alarme pausieren, solange der Pool dünn ist (`alarmCapable`)
        ticker.liquidityUsd = DexScreener.liquidity(of: pool)
    }

    /// Alle Pools der Antwort: zuerst das Array `pairs` (nur Objekte), dann ein einzelnes `pair`.
    private static func poolsIn(_ json: JObject) -> [JObject] {
        var found = json.optArray("pairs")?.optObjects() ?? []
        if let single = json.optObject("pair") { found.append(single) }
        return found
    }

    // MARK: Liquidität

    /// Mindest-Liquidität (USD) eines Pools für Alarme. Darunter (oder unbekannt) bewegen schon kleine Beträge den
    /// Kurs — ein Alarm wäre leicht auszulösen bzw. zu manipulieren. Wie Android `DEX_MIN_ALARM_LIQUIDITY_USD`.
    static let minAlarmLiquidityUsd = 10_000.0

    /// Alarmfähig: bekannte Liquidität von mindestens `minAlarmLiquidityUsd`.
    static func alarmCapable(_ liquidityUsd: Double?) -> Bool {
        guard let liquidityUsd, liquidityUsd.isFinite else { return false }
        return liquidityUsd >= minAlarmLiquidityUsd
    }

    /// «liquidity.usd» eines Pools; fehlend, NaN oder negativ = unbekannt (nil).
    static func liquidity(of entry: JObject) -> Double? {
        guard let usd = entry.optObject("liquidity")?.optDouble("usd"), usd.isFinite, usd >= 0 else { return nil }
        return usd
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

    /// Höchstzahl Pool-Adressen je Sammelabfrage (Vorgabe von DexScreener).
    static let bulkMaxPools = 30

    /// Zeichen, die unkodiert im Pfad stehen dürfen (Chain-Namen, EVM-/Solana-Adressen).
    private static let safePathCharacters = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~-")

    /// Kennung genau «chain/adresse», beide Teile nur aus Zeichen, die unkodiert im Pfad stehen dürfen
    /// (`safePathCharacters`; keine Leerzeichen, kein zweites «/», kein «?», «#», «%»). Gilt für Einzelkurs, Suchtreffer
    /// und Sicherung. Wie Android `DexScreener.isSafePairId` (`Parity/dex_pair_id.json`).
    /// SEC3-R1: kein Teil nur aus Punkten («.», «..» würden den Pfad beim Parsen verlassen).
    static func isSafePairId(_ pairId: String) -> Bool {
        guard let cut = pairId.firstIndex(of: "/") else { return false }
        return isSafePart(pairId[..<cut]) && isSafePart(pairId[pairId.index(after: cut)...])
    }

    /// Ein Teil der Kennung: nicht leer, nur Pfad-Zeichen und nicht nur Punkte.
    private static func isSafePart(_ part: Substring) -> Bool {
        !part.isEmpty && part.allSatisfy { safePathCharacters.contains($0) } && part.contains { $0 != "." }
    }

    /// Pools für die Sammelabfrage: je Chain (sortiert) die Adressen ohne Doppelte, sortiert, in Gruppen von höchstens
    /// `bulkMaxPools`. Kennungen ohne «chain/adresse» oder mit Zeichen, die kodiert werden müssten, fehlen — sie
    /// laufen über die Einzelabfrage. Wie `DexScreener.bulkGroups` (Android).
    static func bulkGroups(_ pairIds: [String]) -> [(chain: String, addresses: [String])] {
        var byChain: [String: Set<String>] = [:]
        for raw in pairIds {
            let id = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let cut = id.firstIndex(of: "/"),
                  isSafePart(id[..<cut]), isSafePart(id[id.index(after: cut)...]) else { continue }
            byChain[String(id[..<cut]), default: []].insert(String(id[id.index(after: cut)...]))
        }
        return byChain.keys.sorted().flatMap { chain -> [(chain: String, addresses: [String])] in
            let addresses = byChain[chain, default: []].sorted()
            return stride(from: 0, to: addresses.count, by: bulkMaxPools).map { start in
                (chain: chain, addresses: Array(addresses[start..<min(start + bulkMaxPools, addresses.count)]))
            }
        }
    }

    /// Adresse einer Gruppe: `/latest/dex/pairs/{chain}/{a,b,…}`.
    static func bulkURL(_ group: (chain: String, addresses: [String])) -> String {
        dexScreenerAPI + "/latest/dex/pairs/" + group.chain + "/" + group.addresses.joined(separator: ",")
    }

    static func searchURL(_ query: String) -> String {
        dexScreenerAPI + "/latest/dex/search?q=" + query.trimmingCharacters(in: .whitespacesAndNewlines).urlQueryEncoded
    }

    /// Gekürzte Adresse: die ersten 6 und die letzten 4 Zeichen («0x1234…abcd»); kurze Adressen bleiben ganz. Wie
    /// `DexScreener.shortAddress` (Android).
    static func shortAddress(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count <= 12 ? trimmed : String(trimmed.prefix(6)) + "…" + String(trimmed.suffix(4))
    }

    /// Treffer der Suche, nach Liquidität absteigend (meist steht der relevante Pool vorn); gleich viel Liquidität
    /// behält die Reihenfolge der Antwort. Ist `query` die Vertragsadresse eines Tokens, stehen dessen Pools (als
    /// Basis) vor allen anderen — sonst stünde ein liquiderer Pool mit dem Token nur als Gegenwährung (z. B.
    /// WETH/PEPE) oben. Wie Android.
    static func parseSearch(_ body: String, query: String = "") throws -> [DexPool] {
        var pools: [DexPool] = []
        for entry in try JObject(string: body).optArray("pairs")?.allObjects() ?? [] {
            guard let base = entry.optObject("baseToken"), let quote = entry.optObject("quoteToken") else { continue }
            pools.append(DexPool(
                chainId: entry.optString("chainId"),
                dexId: entry.optString("dexId"),
                pairAddress: entry.optString("pairAddress"),
                baseSymbol: base.optString("symbol"),
                quoteSymbol: quote.optString("symbol"),
                priceUsd: Double(entry.optString("priceUsd")),
                liquidityUsd: DexScreener.liquidity(of: entry),
                baseAddress: base.optString("address")
            ))
        }
        // Nur Pools, deren Kennung unkodiert in den Pfad darf (`isSafePairId`) — sonst nie übernehmbar
        let usable = pools.filter { pool in
            !pool.chainId.isEmpty && !pool.pairAddress.isEmpty && !pool.baseSymbol.isEmpty && pool.priceUsd != nil
                && DexScreener.isSafePairId(pool.pairId)
        }
        // Stabil sortieren: bei gleicher Liquidität entscheidet die Position in der Antwort
        let ranked = usable.indices.sorted { left, right in
            let leftLiquidity = usable[left].liquidityUsd ?? 0
            let rightLiquidity = usable[right].liquidityUsd ?? 0
            return leftLiquidity != rightLiquidity ? leftLiquidity > rightLiquidity : left < right
        }
        let sorted = ranked.map { usable[$0] }
        let wanted = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if wanted.isEmpty { return sorted }
        let isWanted: (DexPool) -> Bool = { $0.baseAddress.caseInsensitiveCompare(wanted) == .orderedSame }
        return sorted.filter(isWanted) + sorted.filter { !isWanted($0) }
    }
}
