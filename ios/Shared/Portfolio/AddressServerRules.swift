import Foundation

/// Regeln rund um den eigenen Bitcoin-Server (`AddressServer`) für Einstellungen und Abrufe: Eingabe kürzen, Pausen-Schlüssel,
/// Sperr-Ausnahme, Testergebnis zur Eingabe und Rückfrage beim Wechsel auf öffentliche Knoten — wie `AddressServerRules.kt`
/// (Fixture address_server_ui.json).
enum AddressServerRules {
    /// Zu lange Eingabe (z. B. eingefügt) wird gekürzt statt verworfen — auf `AddressServer.maxLength` + 1, damit die
    /// Prüfung (`AddressServer.check`) weiter «ungültig» meldet (genau auf die Grenze gekürzt gälte sie still als gültig).
    static func clamp(_ input: String) -> String {
        input.count > AddressServer.maxLength + 1 ? String(input.prefix(AddressServer.maxLength + 1)) : input
    }

    /// Schlüssel für Pausen und Sperren: Hostname ohne Schema, Port und Pfad.
    static func hostKey(_ entry: String) -> String {
        var host = entry.hasPrefix("https://") ? String(entry.dropFirst(8)) : entry
        if let slash = host.firstIndex(of: "/") { host = String(host[..<slash]) }
        if let colon = host.firstIndex(of: ":") { host = String(host[..<colon]) }
        return host
    }

    /// Bitcoin-Einträge sind der eigene Server (volle Basis mit https://, öffentliche Hosts stehen ohne Schema): kein
    /// 6-h-Sperren nach 403/451 — der Nutzer betreibt ihn selbst und hat keinen Rückfall; nur die kurze Pause gilt.
    static func isOwnServer(_ entries: [String]) -> Bool { entries.contains { $0.hasPrefix("https://") } }

    /// Ergebnis von «Testen» nur anzeigen, wenn es zur aktuell geprüften Eingabe gehört.
    static func testShown(_ testedUrl: String?, inputUrl: String?) -> Bool { testedUrl != nil && testedUrl == inputUrl }

    /// Rückfrage vor dem Wechsel vom eigenen Server (`current`) auf öffentliche Knoten (`next` = nil), wenn
    /// Bitcoin-Adressen oder xpub beobachtet werden (`btcCount`): diese gingen danach an die öffentlichen Hosts.
    static func confirmPublicSwitch(current: String?, next: String?, btcCount: Int) -> Bool {
        current != nil && next == nil && btcCount > 0
    }

    /// Anzahl beobachteter Bitcoin-Einträge (Adressen und xpub); Liste nicht lesbar (nil) → 1: lieber fragen als still
    /// auf öffentliche Knoten wechseln.
    static func btcCount(_ addresses: [WatchedAddress]?) -> Int {
        guard let addresses else { return 1 }
        return addresses.filter { $0.chain == .BTC }.count
    }
}
