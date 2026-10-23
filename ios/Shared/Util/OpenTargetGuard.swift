import Foundation
import Security

/// Ziel eines `cryptochecker://`-Links nur von der App selbst: Jede andere App und jede Webseite kann
/// das Schema öffnen — etwa direkt in den Portfolio-Tab. Darum tragen die Links der eigenen Widgets ein Geheimnis dieser
/// Installation mit (`?t=…`, gespeichert in der App Group, die nur App und Widgets lesen); ohne passendes Geheimnis
/// gelten nur die harmlosen Ziele (wie die Schnellaktionen) und «Paar öffnen». Mitteilungen und Schnellaktionen
/// gehen nicht über Links und sind davon nicht betroffen. Die Portfolio-Sperre bleibt unabhängig davon.
/// Gleiche Regel wie Android `OpenTargetGuard`.
enum OpenTargetGuard {

    /// Ziele, die auch ohne Geheimnis gelten (Schnellaktionen bzw. Android-Verknüpfungen; nur Navigation).
    static let publicTargets: Set<String> = ["add", "alarms", "cycle"]

    /// Name des Abfrage-Parameters mit dem Geheimnis.
    static let queryName = "t"

    private static let defaultsKey = "open_target_token"
    private static let lock = NSLock()

    /// Gilt `target`? Öffentliche Ziele immer, alle anderen (Portfolio, Markt-Termine, «Was bewegt sich mit?», Alarm
    /// bearbeiten) nur mit dem Geheimnis `expected` in `token`. Rein, getestet — wie Android `OpenTargetGuard.accepts`.
    static func accepts(_ target: String, token: String?, expected: String) -> Bool {
        if isPublic(target) { return true }
        guard let token, !expected.isEmpty else { return false }
        return sameToken(token, expected)
    }

    /// Ohne Geheimnis erlaubt: Schnellaktions-Ziele und «Paar öffnen» (`watch/<id>`).
    static func isPublic(_ target: String) -> Bool {
        if publicTargets.contains(target) { return true }
        guard target.hasPrefix("watch/"), let id = Int64(target.dropFirst(6)) else { return false }
        return id > 0
    }

    /// Vergleich in konstanter Zeit (verrät über die Laufzeit nichts vom Geheimnis).
    private static func sameToken(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in x.indices { diff |= x[i] ^ y[i] }
        return diff == 0
    }

    /// Mitgegebenes Geheimnis eines Links (`cryptochecker://portfolio?t=…`); nil ohne.
    static func linkToken(of url: URL) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == queryName }?.value
    }

    /// Link zu `target` (ein Wort ohne «/», z. B. «portfolio») mit dem Geheimnis — für die eigenen Widgets.
    static func url(_ target: String) -> URL? {
        var components = URLComponents()
        components.scheme = "cryptochecker"
        components.host = target
        components.queryItems = [URLQueryItem(name: queryName, value: token)]
        return components.url
    }

    /// Geheimnis dieser Installation; beim ersten Mal (App oder Widget) erzeugt (128 Bit Zufall) und gespeichert.
    static var token: String {
        lock.lock()
        defer { lock.unlock() }
        let defaults = SharedStorage.defaults
        if let stored = defaults.string(forKey: defaultsKey), !stored.isEmpty { return stored }
        var bytes = [UInt8](repeating: 0, count: 16)
        if SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) != errSecSuccess {
            bytes = Array(UUID().uuidString.utf8.prefix(16))
        }
        let created = bytes.map { String(format: "%02x", $0) }.joined()
        defaults.set(created, forKey: defaultsKey)
        // App und Widget sind getrennte Prozesse: hat der andere gleichzeitig eines angelegt, gilt das zuletzt
        // geschriebene — darum den gespeicherten Wert zurückgeben, nicht den eigenen
        return defaults.string(forKey: defaultsKey) ?? created
    }
}
