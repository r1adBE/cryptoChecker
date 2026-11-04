import Foundation

/// Paar eines Favoriten des iPhones für «Wie iPhone»: Basis, Gegenwährung, Börse (Name wie auf dem iPhone, z. B.
/// «Binance Futures») und Kontrakt (`contract`: leer = Spot, sonst die Kurzform des iPhones «Perp», «1Q» …; `pool` + 8
/// Hex-Stellen = DexScreener-Pool, die Stellen aus seiner Kennung, damit zwei Pools mit gleicher Basis/Quote getrennt
/// bleiben; eine Börsen-Kennung wie «BTCUSDT» macht kein Pool). `key` ist der Schlüssel in Liste, Kursen und Speicher
/// der Uhr; Coins der eigenen Liste behalten ihr Kürzel («BTC» = gegen USDT, Spot, erste Börse, die liefert). Die Uhr zeigt den Kurs dieses Paars:
/// vom iPhone oder selbst abgefragt zuerst bei derselben Börse und Kontraktart (`selfFetchable`). Wie Android
/// `WatchPair` (`Parity/watch_settings.json`, `watch_link.json`, `watch_quotes.json`).
struct WatchPair: Equatable, Hashable, Sendable {
    static let usdt = "USDT"
    static let perpetual = "Perp"
    static let pool = "Pool"
    /// Perpetual-Börsen, die die Uhr selbst abfragt (Namen wie auf dem iPhone; `WatchPairQuotes.Venue`).
    static let perpExchanges = ["Binance Futures", "Bybit Futures", "OKX Futures"]
    private static let maxExchange = 40

    let base: String
    let quote: String
    let exchange: String
    let contract: String

    /// Schlüssel «BASE/QUOTE|Börse|Kontrakt», z. B. «BANK/USDT|Binance Futures|Perp».
    var key: String { "\(base)/\(quote)|\(exchange)|\(contract)" }

    /// Anzeige wie auf dem iPhone: «BANK Perp», «BTC/EUR», «ETH» (Gegenwährung nur, wenn nicht USDT).
    var label: String {
        var text = base
        if quote != Self.usdt { text += "/" + quote }
        if !contract.isEmpty && !isPool { text += " " + contract }
        return text
    }

    /// DexScreener-Pool (`pool` + Kennungs-Stellen, `contract(short:pool:)`).
    var isPool: Bool { contract.hasPrefix(Self.pool) }

    /// Lineares Perpetual (Kurzform «Perp» des iPhones).
    var perp: Bool { contract == Self.perpetual }

    /// Kann die Uhr den Kurs selbst abfragen? Spot und Perpetuals der Börsen, die sie kennt (`perpExchanges`); ein
    /// Perp anderer Börsen (Bitget, Hyperliquid …), andere Kontrakte und Pools nur vom iPhone — nie ein fremder Kurs
    /// unter dem Namen dieses Paars.
    var selfFetchable: Bool {
        contract.isEmpty || (perp && Self.perpExchanges.contains { $0.caseInsensitiveCompare(exchange) == .orderedSame })
    }

    /// Kontrakt-Marke aus der Kurzform des iPhones (`short`, nil = Spot) und der Pool-Kennung (`pool` =
    /// `WatchIdentity.pool`: nur DexScreener, sonst nil — die Kennung einer Börse wie «BTCUSDT» zählt nicht):
    /// Kurzform, sonst `pool` + `poolTag`, sonst leer (Spot).
    static func contract(short: String?, pool id: String?) -> String {
        if let short { return short }
        guard let id, !id.isEmpty else { return "" }
        return pool + poolTag(id)
    }

    /// 8 Hex-Stellen (FNV-1a, 32 Bit, über UTF-8) der Pool-Kennung — gleich wie Android `WatchPair.poolTag`.
    static func poolTag(_ id: String) -> String {
        var h: UInt32 = 0x811c_9dc5
        for b in id.utf8 { h = (h ^ UInt32(b)) &* 0x0100_0193 }
        let hex = String(h, radix: 16)
        return String(repeating: "0", count: 8 - hex.count) + hex
    }

    /// Bereinigt (gross, ohne Leerzeichen, Börse ohne «|», höchstens 40 Zeichen, Kontrakt höchstens 12); ungültig → nil.
    static func of(base: String, quote: String, exchange: String, contract: String) -> WatchPair? {
        let b = base.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let q = quote.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let x = String(exchange.replacingOccurrences(of: "|", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxExchange))
        let c = contract.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isSymbol(b), isSymbol(q), !x.isEmpty, c.count <= 12,
              c.unicodeScalars.allSatisfy({ isAlnum($0) }) else { return nil }
        return WatchPair(base: b, quote: q, exchange: x, contract: c)
    }

    /// Schlüssel (`key`) → Paar; ein Kürzel der eigenen Liste oder Unlesbares → nil.
    static func parse(_ key: String) -> WatchPair? {
        let parts = key.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3 else { return nil }
        let legs = parts[0].split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard legs.count == 2, let pair = of(base: legs[0], quote: legs[1], exchange: parts[1], contract: parts[2]),
              pair.key == key else { return nil }
        return pair
    }

    /// Anzeige eines Schlüssels: Paar wie auf dem iPhone, sonst das Kürzel.
    static func label(of key: String) -> String { parse(key)?.label ?? key }

    private static func isSymbol(_ s: String) -> Bool {
        (2...12).contains(s.count) && s.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
    }

    private static func isAlnum(_ u: Unicode.Scalar) -> Bool {
        ("A"..."Z").contains(u) || ("a"..."z").contains(u) || ("0"..."9").contains(u)
    }
}
