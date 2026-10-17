import Foundation

/// User-Agent aller Netzabfragen (Börsen, Kerzen, Logos, Adressen), wie Android
/// (`HttpUserAgent.kt`, «cryptoChecker-Android/…»). Version aus dem Bundle
/// (CFBundleShortVersionString) — in der Widget-Erweiterung deren eigenes Bundle (gleiche Version).
enum HttpUserAgent {
    static let header = "User-Agent"

    /// «cryptoChecker-iOS/16.2.2»; ohne lesbare Version nur die Hauptversion.
    static func ios(_ version: String?) -> String {
        let v = version?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return "cryptoChecker-iOS/\(v.isEmpty ? "16" : v)"
    }

    /// Wert für diese App bzw. Erweiterung (einmal gelesen).
    static let value: String = ios(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
}
