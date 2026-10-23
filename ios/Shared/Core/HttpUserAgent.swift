import Foundation

/// User-Agent aller Netzabfragen (Börsen, Kerzen, Logos; Adress-Hosts: `neutral` bei jeder Anfrage
/// über `MarketHTTP.sessionFor(host:requested:)`), wie Android
/// (`HttpUserAgent.kt`, «cryptoChecker-Android/…»). Version aus dem Bundle
/// (CFBundleShortVersionString) — in der Widget-Erweiterung deren eigenes Bundle (gleiche Version).
enum HttpUserAgent {
    static let header = "User-Agent"

    /// «cryptoChecker-iOS/16.2.2»; ohne lesbare Version nur die Hauptversion.
    static func ios(_ version: String?) -> String {
        let v = version?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return "cryptoChecker-iOS/\(v.isEmpty ? "16" : v)"
    }

    /// Neutraler User-Agent für Adress-Hosts (verbreiteter vollständiger Browser-Wert:
    /// Firefox ESR 153): ausdrücklich gesetzt, sonst sendet CFNetwork den Bundle-Namen. Nennt weder App noch Version,
    /// tarnt die Anfrage aber nicht als Browser (TLS-/HTTP-Merkmale, IP und Abfragemuster bleiben die der App).
    /// Gleicher Wert wie Android (`Parity/address_privacy.json`).
    static let neutral = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    /// Hosts, die eine Adresse zu sehen bekommen (`AddressBalance.privacyHosts`).
    private static let addressHosts: Set<String> = Set(AddressBalance.privacyHosts.map { $0.lowercased() })

    /// Bekommt dieser Host Adressen? Dann bei JEDER Anfrage `neutral` — auch Gebühren und Halving (mempool.space,
    /// publicnode …), sonst verbände der Host App-Kennung und Adresse. Wie Android `HttpUserAgent.isAddressHost`.
    static func isAddressHost(_ host: String) -> Bool {
        var h = host.lowercased()
        while h.hasSuffix(".") { h.removeLast() }
        return addressHosts.contains(h)
    }

    /// User-Agent für eine Anfrage an `host`: Adress-Hosts `neutral`, sonst `app`.
    static func forHost(_ host: String, app: String = HttpUserAgent.value) -> String { isAddressHost(host) ? neutral : app }

    /// Wert für diese App bzw. Erweiterung (einmal gelesen).
    static let value: String = ios(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
}
