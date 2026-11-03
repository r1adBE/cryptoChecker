import Foundation

/// Paar eines Favoriten des iPhones für «Wie iPhone»: Basis, Gegenwährung, Börse (Name wie auf dem iPhone, z. B.
/// «Binance Futures») und Kontrakt (`contract`: leer = Spot, sonst die Kurzform des iPhones «Perp», «1Q» …; `pool` =
/// Pool mit Kennung, z. B. DexScreener). `key` ist der Schlüssel in Liste, Kursen und Speicher der Uhr; Coins der eigenen
/// Liste behalten ihr Kürzel («BTC» = gegen USDT, Spot, erste Börse, die liefert). Die Uhr zeigt den Kurs dieses Paars:
/// vom iPhone oder selbst abgefragt zuerst bei derselben Börse und Kontraktart (`selfFetchable`). Wie Android
/// `WatchPair` (`Parity/watch_settings.json`, `watch_link.json`, `watch_quotes.json`).
struct WatchPair: Equatable, Hashable, Sendable {
    static let usdt = "USDT"
    static let perpetual = "Perp"
    static let pool = "Pool"
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
        if !contract.isEmpty && contract != Self.pool { text += " " + contract }
        return text
    }

    /// Lineares Perpetual (Kurzform «Perp» des iPhones).
    var perp: Bool { contract == Self.perpetual }

    /// Kann die Uhr den Kurs selbst abfragen? Spot und Perpetuals; andere Kontrakte und Pools nur vom iPhone.
    var selfFetchable: Bool { contract.isEmpty || perp }

    /// Kontrakt-Marke aus der Kurzform des iPhones (`short`, nil = Spot) und ob das Paar eine Kennung hat (Pool).
    static func contract(short: String?, hasPairId: Bool) -> String { short ?? (hasPairId ? pool : "") }

    /// Bereinigt (gross, ohne Leerzeichen, Börse ohne «|», höchstens 40 Zeichen); ungültig → nil.
    static func of(base: String, quote: String, exchange: String, contract: String) -> WatchPair? {
        let b = base.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let q = quote.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let x = String(exchange.replacingOccurrences(of: "|", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxExchange))
        let c = contract.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isSymbol(b), isSymbol(q), !x.isEmpty, c.count <= 8,
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
