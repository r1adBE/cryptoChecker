import Foundation

/// Freigabe einer Logo-Adresse (`CoinLogos.isAllowedURL`): mit echtem URL-Parser statt
/// ausgeschnittenem Host: Ein «#» oder «\» in der Authority (der Parser beendet den Host dort) kann die Liste nicht
/// umgehen. Gleich in Android `LogoUrlPolicy` (gemeinsame Fälle `Parity/logo_url_allow.json`).
///
/// - nur `https://`, nur URL-Zeichen in ASCII (kein Leerzeichen, kein «\», kein «#» = kein Fragment, «%» nur mit zwei
///   Hex-Ziffern); Authority ohne «@» (Benutzer/Passwort) und ohne «%»
/// - Host nur aus DNS-Labels (a–z 0–9 «-», keine leeren Labels, kein «xn--»), Port fehlt oder genau 443
/// - der Parser (`URLComponents`) muss denselben Host, Port 443 oder keinen, kein Benutzer/Passwort und kein Fragment
///   ergeben
/// - Host `coingecko.com`, `*.coingecko.com` oder `*.bnbstatic.com`; kein Platzhalter `/missing_…`
enum LogoURLPolicy {
    private static let prefix = "https://"
    private static let urlCharacters =
        Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" + "-._~:/?@!$&'()*+,;=%")
    private static let labelCharacters = Set("abcdefghijklmnopqrstuvwxyz0123456789-")
    private static let hexDigits = Set("0123456789abcdefABCDEF")

    static func isAllowed(_ url: String?) -> Bool {
        guard let url, url.hasPrefix(prefix), url.allSatisfy({ urlCharacters.contains($0) }), validPercents(url)
        else { return false }
        let authority = String(url.dropFirst(prefix.count).prefix { $0 != "/" && $0 != "?" })
        if authority.isEmpty || authority.contains("@") || authority.contains("%") { return false }
        var host = authority.lowercased()
        if host.contains(":") {
            guard host.hasSuffix(":443") else { return false }
            host.removeLast(4)
        }
        if host.contains(":") || !host.split(separator: ".", omittingEmptySubsequences: false).allSatisfy(isLabel) {
            return false
        }
        guard let parsed = URLComponents(string: url), parsed.scheme == "https", parsed.host?.lowercased() == host,
              parsed.port == nil || parsed.port == 443, parsed.user == nil, parsed.password == nil,
              parsed.fragment == nil else { return false }
        let known = host == "coingecko.com" || host.hasSuffix(".coingecko.com") || host.hasSuffix(".bnbstatic.com")
        // Platzhalter für Coins ohne Logo («missing_large.png»): lieber Initialen
        return known && !url.contains("/missing_")
    }

    /// DNS-Label: a–z 0–9 «-», nicht leer, nicht am Rand «-», kein «xn--» (wie Android `LABEL`).
    private static func isLabel(_ label: Substring) -> Bool {
        !label.isEmpty && label.allSatisfy { labelCharacters.contains($0) } && label.first != "-"
            && label.last != "-" && !label.hasPrefix("xn--")
    }

    /// «%» nur als Kodierung mit zwei Hex-Ziffern (sonst lesen OkHttp und Foundation die Adresse verschieden).
    private static func validPercents(_ url: String) -> Bool {
        let chars = Array(url)
        return chars.indices.filter { chars[$0] == "%" }.allSatisfy { i in
            i + 2 < chars.count && hexDigits.contains(chars[i + 1]) && hexDigits.contains(chars[i + 2])
        }
    }
}
