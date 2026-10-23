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
    /// ohne Zwischenspeicher im Hintergrund) und die noch «laden» (`AddressAlarmState.isPending`): sie fehlen in `holdings`,
    /// der Gesamtwert ist dann zu tief — Portfolio-Alarme prüfen nicht (`PortfolioReading.addressesPending`).
    /// Eine Adresse, die seit über 24 Stunden scheitert, zählt nicht (`AddressAlarmState.failing`). Regeln wie
    /// `AddressAlarmState.of`.
    static func pendingCount(_ addresses: [WatchedAddress], now: Int64) -> Int {
        AddressAlarmState.of(addresses, now: now).pending
    }
}
