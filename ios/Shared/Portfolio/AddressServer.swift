import Foundation

/// Eigener Server für Adressabfragen (Einstellungen › Portfolio), nur Bitcoin: Esplora-/mempool-kompatible API, z. B.
/// «https://knoten.example/api» — wie `AddressServer.kt` (dort begründet; Fixture address_server.json). Gesetzt:
/// Bitcoin-Adressen und xpub fragen nur dort, ohne Rückfall auf öffentliche Knoten; leer: `AddressBalance.btcHosts`.
/// Erlaubt: nur https, Host (Name oder IPv4) mit optionalem Port und Pfad; kein Benutzer/Passwort, keine Abfrage, kein
/// Fragment, nur sichtbare ASCII-Zeichen.
enum AddressServer {
    /// Längste erlaubte Eingabe (Zeichen).
    static let maxLength = 200
    /// Gespeicherter Fehler einer Bitcoin-Adresse, wenn der eigene Server nicht liefert (sprachunabhängig). Wie Android.
    static let failedMarker = "OWN_SERVER_FAILED"

    enum Problem: String, Sendable { case NOT_HTTPS, USER_INFO, QUERY_OR_FRAGMENT, INVALID }

    /// Ergebnis der Prüfung: `url` normalisiert (nil bei leerer Eingabe = öffentliche Knoten oder bei `problem`).
    struct Check: Equatable, Sendable {
        let url: String?
        let problem: Problem?
    }

    private static let scheme = "https://"
    private static let pathExtra = Set("/-._~")

    static func check(_ input: String) -> Check {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return Check(url: nil, problem: nil) }
        if text.count > maxLength || text.unicodeScalars.contains(where: { $0.value < 0x21 || $0.value > 0x7e }) {
            return invalid(.INVALID)
        }
        guard text.lowercased().hasPrefix(scheme) else { return invalid(.NOT_HTTPS) }
        let rest = String(text.dropFirst(scheme.count))
        if rest.contains("?") || rest.contains("#") { return invalid(.QUERY_OR_FRAGMENT) }
        let authority = rest.firstIndex(of: "/").map { String(rest[..<$0]) } ?? rest
        if authority.contains("@") { return invalid(.USER_INFO) }
        var path = String(rest.dropFirst(authority.count))
        while path.hasSuffix("/") { path.removeLast() }
        if path.contains(where: { !(isAsciiAlnum($0) || pathExtra.contains($0)) }) { return invalid(.INVALID) }
        let parts = authority.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        if parts.count > 2 { return invalid(.INVALID) }
        let host = parts[0].lowercased()
        guard validHost(host) else { return invalid(.INVALID) }
        var portText = ""
        if parts.count == 2 {
            guard let port = parsePort(parts[1]) else { return invalid(.INVALID) }
            if port != 443 { portText = ":\(port)" }
        }
        return Check(url: scheme + host + portText + path, problem: nil)
    }

    /// Hosts für Bitcoin-Abfragen: nur der eigene Server, sonst die öffentlichen.
    static func btcEntries(_ server: String?) -> [String] { server.map { [$0] } ?? AddressBalance.btcHosts }

    /// Adress-URL: eigener Server (volle Basis mit https://) oder öffentlicher Host.
    static func btcUrl(_ entry: String, address: String) -> String {
        entry.hasPrefix(scheme) ? "\(entry)/address/\(address)" : AddressBalance.btcUrl(host: entry, address: address)
    }

    /// «Testen»: aktuelle Blockhöhe (Esplora `GET /blocks/tip/height`, nennt keine Adresse).
    static func testUrl(_ server: String) -> String { "\(server)/blocks/tip/height" }

    /// Antwort von `testUrl`: Blockhöhe > 0, sonst nil (kein Esplora-Server).
    static func parseTipHeight(_ body: String) -> Int64? {
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...9).contains(t.count), let v = Int64(t), v > 0 else { return nil }
        return v
    }

    /// Host für Hinweise («knoten.example»).
    static func host(_ server: String) -> String { ChainBalance.host(server) }

    /// Zustimmung vor dem Hinzufügen: vor der ersten Adresse überhaupt und vor dem ersten xpub/Deskriptor (alle
    /// abgeleiteten Adressen gehen an denselben Knotenbetreiber).
    static func consentNeeded(addressCount: Int, xpubCount: Int, newIsKey: Bool) -> Bool {
        addressCount == 0 || (newIsKey && xpubCount == 0)
    }

    /// Wie oben für die Liste und die geprüfte Eingabe (xpub/ypub/zpub/Taproot-Konto gelten als Schlüssel).
    static func consentNeeded(_ addresses: [WatchedAddress], check: AddressValidation.Check) -> Bool {
        let keyKinds: Set<AddressValidation.Kind> = [.XPUB, .YPUB, .ZPUB, .TR_XPUB]
        return consentNeeded(addressCount: addresses.count, xpubCount: addresses.filter(\.isXpub).count,
                             newIsKey: check.kind.map { keyKinds.contains($0) } ?? false)
    }

    /// Wert aus einer Sicherung: ungültig: bleibt `current`; leer/nil löscht.
    /// Nur eine Sicherung mit Passwort (`trusted`) ändert ihn: Eine Datei ohne Echtheitsprüfung setzt, ersetzt und
    /// löscht nie (sonst gingen die Bitcoin-Adressen an einen fremden Server bzw. an öffentliche Knoten).
    static func restored(value: String?, current: String?, trusted: Bool) -> String? {
        guard trusted else { return current }
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let c = check(value)
        return c.problem == nil ? c.url : current
    }

    private static func invalid(_ problem: Problem) -> Check { Check(url: nil, problem: problem) }

    private static func isAsciiAlnum(_ c: Character) -> Bool {
        guard let a = c.asciiValue else { return false }
        return (48...57).contains(a) || (65...90).contains(a) || (97...122).contains(a)
    }

    private static func parsePort(_ text: String) -> Int? {
        guard (1...5).contains(text.count), text.allSatisfy({ $0.asciiValue.map { (48...57).contains($0) } ?? false }),
              let port = Int(text), (1...65535).contains(port) else { return nil }
        return port
    }

    /// Name (Teile aus a–z, 0–9, «-», nicht am Rand) oder IPv4 (vier Zahlen 0–255 ohne führende Null).
    private static func validHost(_ host: String) -> Bool {
        guard !host.isEmpty, host.count <= 253 else { return false }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        if host.allSatisfy({ $0 == "." || ("0"..."9").contains($0) }) {
            return labels.count == 4 && labels.allSatisfy { l in
                (1...3).contains(l.count) && (l.count == 1 || l.first != "0") && (Int(l) ?? 256) <= 255
            }
        }
        return labels.allSatisfy { l in
            (1...63).contains(l.count) && l.first != "-" && l.last != "-"
                && l.allSatisfy { $0 == "-" || ($0.asciiValue.map { (48...57).contains($0) || (97...122).contains($0) } ?? false) }
        }
    }
}
