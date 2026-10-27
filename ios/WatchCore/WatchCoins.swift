import Foundation

/// Coins der Uhr-Apps (Build 155): Auswahl der gängigen Coins gegen USDT, höchstens `max` je Liste. Gemeinsam für die
/// iPhone-App (Einstellungen › Widgets › Uhr) und die Apple-Watch-App. Wie Android `WatchCoins` (`watch_settings.json`).
enum WatchCoins {
    static let max = 8

    static let top = [
        "BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX", "TON", "AVAX",
        "SHIB", "DOT", "LINK", "BCH", "NEAR", "LTC", "UNI", "APT", "ICP", "ETC",
        "XLM", "FIL", "HBAR", "ATOM", "ARB", "OP", "SUI", "PEPE", "INJ", "RENDER",
    ]

    static let defaultOwn = ["BTC", "ETH"]

    /// Eintrag umschalten: hinzufügen (am Ende, solange unter `max`) oder entfernen.
    static func toggle(_ list: [String], _ coin: String) -> [String] {
        if list.contains(coin) { return list.filter { $0 != coin } }
        if list.count >= max { return list }
        return list + [coin]
    }

    /// Eigene Liste bereinigen: nur Coins aus `top`, ohne Doppelte, höchstens `max`.
    static func cleanOwn(_ stored: [String]) -> [String] {
        Array(unique(stored.filter { top.contains($0) }).prefix(max))
    }

    /// «Wie iPhone»: Basis-Coins der Favoriten in ihrer Reihenfolge — gross, nur gültige Kürzel (`[A-Z0-9]{2,12}`),
    /// ohne Doppelte, höchstens `max`; die Uhr zeigt sie gegen USDT.
    static func fromFavorites(_ bases: [String]) -> [String] {
        let cleaned = bases.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }.filter(isSymbol)
        return Array(unique(cleaned).prefix(max))
    }

    private static func isSymbol(_ s: String) -> Bool {
        (2...12).contains(s.count) && s.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
    }

    private static func unique(_ list: [String]) -> [String] {
        var seen = Set<String>()
        return list.filter { seen.insert($0).inserted }
    }
}
