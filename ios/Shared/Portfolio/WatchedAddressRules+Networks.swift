import Foundation

// Erste Wahl der EVM-Netze einer 0x-Adresse (Teil von `WatchedAddressRules`) — wie Android
// `WatchedAddressRules.discoveredNetworks`.

extension WatchedAddressRules {
    /// Erste Suche einer 0x-Adresse: Netze mit Guthaben (`found`) plus die, die nicht antworteten (`unanswered`) — ein
    /// stummes Netz fiele sonst für immer aus der Wahl und würde nie wieder gefragt. Im Hintergrund nie (nil = Wahl
    /// bleibt offen, die nächste Runde fragt wieder alle).
    static func discoveredNetworks(found: [String], unanswered: [String], background: Bool) -> [String]? {
        background ? nil : cleanNetworks(found + unanswered)
    }
}
