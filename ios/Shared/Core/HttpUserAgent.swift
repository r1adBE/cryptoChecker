import Foundation

/// User-Agent aller Netzabfragen (Börsen, Kerzen, Logos; Adressen: `neutral`), wie Android
/// (`HttpUserAgent.kt`, «cryptoChecker-Android/…»). Version aus dem Bundle
/// (CFBundleShortVersionString) — in der Widget-Erweiterung deren eigenes Bundle (gleiche Version).
enum HttpUserAgent {
    static let header = "User-Agent"

    /// «cryptoChecker-iOS/16.2.2»; ohne lesbare Version nur die Hauptversion.
    static func ios(_ version: String?) -> String {
        let v = version?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return "cryptoChecker-iOS/\(v.isEmpty ? "16" : v)"
    }

    /// Neutraler User-Agent ohne App-Name und -Version für Adressabfragen (seit Build 103): ausdrücklich gesetzt,
    /// sonst sendet CFNetwork den Bundle-Namen. Gleicher Wert wie Android (`Parity/address_privacy.json`).
    static let neutral = "Mozilla/5.0"

    /// Wert für diese App bzw. Erweiterung (einmal gelesen).
    static let value: String = ios(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
}
