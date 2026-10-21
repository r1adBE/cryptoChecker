import Foundation

/// «Adresse entfernen» mit «Rückgängig» (seit Build 94): Was geht mit der Adresse weg (für den Text der Rückfrage), und
/// darf die entfernte Adresse mit ihrer alten Id zurück? Rein, gleich wie `WatchedAddressUndo.kt` (Android).
enum WatchedAddressUndo {

    /// Was mit der Adresse verloren ginge: Zahl der Eröffnungsposten und ob ein Guthaben-Verlauf (auch Token) da ist.
    struct Removal: Equatable {
        let lots: Int
        let history: Bool
        var isEmpty: Bool { lots == 0 && !history }
    }

    static func removal(_ address: WatchedAddress) -> Removal {
        Removal(lots: address.costLots.count,
                history: !address.observations.isEmpty || address.tokenObservations.values.contains { !$0.isEmpty })
    }

    /// Zurück mit derselben Id nur, wenn die Id frei ist, dieselbe Adresse (Netz + Adresse) nicht inzwischen neu
    /// hinzugefügt wurde und die Liste nicht voll ist (`WatchedAddressRules.maxAddresses`).
    static func canRestore(_ existing: [WatchedAddress], _ removed: WatchedAddress) -> Bool {
        removed.id > 0
            && !existing.contains { $0.id == removed.id }
            && !WatchedAddressRules.contains(existing, chain: removed.chain, address: removed.address)
            && WatchedAddressRules.canAdd(existing)
    }

    /// Entfernte Adresse mit alter Id wieder in die Datei (`canRestore`); `nextId` bleibt über allen vergebenen Ids.
    /// false, wenn das nicht mehr geht.
    @discardableResult
    static func restore(_ removed: WatchedAddress, into file: inout WatchedAddressFile) -> Bool {
        guard canRestore(file.addresses, removed) else { return false }
        file.addresses.append(removed)
        file.nextId = max(file.nextId, removed.id + 1)
        return true
    }

    /// Warum «Rückgängig» nicht ging (seit Build 95: Banner statt still nichts). Wie Android.
    enum RestoreFailure: Equatable, Sendable { case duplicate, limit, notSaved }

    /// Grund nach einem gescheiterten Zurückholen: dieselbe Adresse inzwischen neu hinzugefügt, Liste voll — sonst nicht
    /// gespeichert (Id belegt, Datei gerade nicht beschreibbar). Wie Android `failure`.
    static func failure(_ existing: [WatchedAddress], _ removed: WatchedAddress) -> RestoreFailure {
        if WatchedAddressRules.contains(existing, chain: removed.chain, address: removed.address) { return .duplicate }
        if !WatchedAddressRules.canAdd(existing) { return .limit }
        return .notSaved
    }
}
