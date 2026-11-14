import Foundation

/// Guthaben fällt auf 0: Eine einzige Antwort «0» — oft «leer, aber gültig» (TON-Jettons `[]`, TRON `{}`,
/// Koios `[]`, XRP `actNotFound`, Solana ohne Token-Konto) — gilt erst, wenn der NÄCHSTE Abruf sie bestätigt. Bis dahin
/// bleibt der alte Wert, der Schlüssel steht in `WatchedAddress.zeroPending` (Token zusätzlich in
/// `WatchedAddress.staleTokens`): Anzeige «nicht bestätigt», Portfolio-Alarme warten wie bei einem alten Kurs
/// (`AddressAlarmState`). Gleiche Regel für jede Kette und jeden Schlüssel; `main` = Guthaben der Kette selbst.
/// Wie Android `ZeroConfirm` (Fälle in `address_balance.json`, «zeroConfirm»).
enum ZeroConfirm {
    /// Schlüssel des Ketten-Guthabens (`WatchedAddress.lastBalance`) in `WatchedAddress.zeroPending`.
    static let main = "*"

    /// Zu übernehmendes Guthaben, Token und die Schlüssel, deren 0 noch auf Bestätigung wartet.
    struct Result: Equatable {
        let balance: String
        let tokens: [String: String]?
        let pending: [String]
    }

    static func apply(_ a: WatchedAddress, balance: String, tokens: [String: String]?) -> Result {
        var pending: [String] = []
        var main = balance
        if holds(a, key: Self.main, old: a.lastBalance, new: balance) {
            pending.append(Self.main)
            main = a.lastBalance ?? balance
        }
        var checked: [String: String]?
        if let tokens {
            var out: [String: String] = [:]
            for key in tokens.keys.sorted() {
                guard let text = tokens[key] else { continue }
                if let old = a.tokenBalances[key], holds(a, key: key, old: old, new: text) {
                    pending.append(key)
                    out[key] = old
                } else {
                    out[key] = text
                }
            }
            checked = out
        }
        // Noch wartende Token, die dieser Abruf nicht gelesen hat (Token-Abfrage gescheitert): warten weiter
        for key in a.zeroPending where key != Self.main && tokens?[key] == nil && !pending.contains(key) {
            pending.append(key)
        }
        return Result(balance: main, tokens: checked, pending: pending)
    }

    /// Neu 0, vorher > 0 und noch nicht einmal gemeldet: alten Wert halten.
    private static func holds(_ a: WatchedAddress, key: String, old: String?, new: String) -> Bool {
        guard let next = PortfolioDecimal.parse(new), let before = PortfolioDecimal.parse(old) else { return false }
        return next == 0 && before > 0 && !a.zeroPending.contains(key)
    }
}
