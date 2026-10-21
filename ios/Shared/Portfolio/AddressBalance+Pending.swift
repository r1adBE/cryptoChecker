import Foundation

// Abruf-Reihenfolge und nie geladene Guthaben (Teil von `AddressBalance`) — wie Android `AddressBalance.dueOrder` und
// `AddressBalance.pendingCount`.

extension AddressBalance {
    /// Fällige Adressen (`isDue`) in Abruf-Reihenfolge: Vordergrund wie gespeichert; Hintergrund die am längsten nicht
    /// versuchten zuerst (nie versucht = 0 vorne, bei Gleichstand wie gespeichert) — das knappe Zeitbudget käme sonst
    /// immer nur den zuerst erfassten Adressen zugute.
    static func dueOrder(_ addresses: [WatchedAddress], now: Int64, force: Bool, background: Bool) -> [WatchedAddress] {
        let due = addresses.filter { isDue(lastAttempt: $0.lastAttempt, now: now, force: force) }
        guard background else { return due }
        // Stabil sortieren: Position als zweiter Schlüssel
        return due.enumerated()
            .sorted { (max(0, $0.element.lastAttempt), $0.offset) < (max(0, $1.element.lastAttempt), $1.offset) }
            .map(\.element)
    }

    /// Zählende Adressen (`countInPortfolio`), deren Guthaben noch nie geladen wurde (z. B. frisch wiederhergestellt, xpub
    /// ohne Zwischenspeicher im Hintergrund): sie fehlen in `holdings`, der Gesamtwert ist dann zu tief — Portfolio-Alarme
    /// prüfen nicht (`PortfolioReading.addressesPending`). Einzelne Bitcoin-Adressen in einem beobachteten xpub zählen
    /// über den xpub, gleiche Adressen einmal.
    static func pendingCount(_ addresses: [WatchedAddress]) -> Int {
        let counted = addresses.filter(\.countInPortfolio)
        let covered = WatchedAddressRules.coveredByXpub(counted)
        let loaded = counted.filter { a in
            guard let value = PortfolioDecimal.parse(a.lastBalance) else { return false }
            return value >= 0
        }
        func same(_ x: WatchedAddress, _ y: WatchedAddress) -> Bool {
            x.chain == y.chain && WatchedAddressRules.sameAddress(x.chain, x.address, y.address)
        }
        var count = 0
        for (i, a) in counted.enumerated() {
            if a.chain == .BTC && !a.isXpub && covered.contains(a.address) { continue }
            if loaded.contains(where: { same($0, a) }) { continue }
            // Gleiche Adresse doppelt ohne Guthaben: einmal
            if counted[..<i].contains(where: { same($0, a) }) { continue }
            count += 1
        }
        return count
    }
}
