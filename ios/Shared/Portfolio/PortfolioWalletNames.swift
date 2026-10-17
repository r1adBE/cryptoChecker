import Foundation

/// Build 46: Namen von Börsen/Wallets bei der Eingabe (Umbuchung «Von»/«Nach»). Bei FIFO je Wallet ist jeder
/// anders geschriebene Name (`PortfolioWallets.key`) eine eigene Wallet — «Binanse» statt «Binance» hätte einen
/// zweiten Topf. Deshalb: bekannte Namen vorschlagen (`suggestions`) und bei einem fast gleichen Namen nachfragen
/// (`nearDuplicate`, «Meintest du Binance?»). Vergleich ohne Gross/klein, Leerzeichen und Akzente (`fold`);
/// Tippfehler über die Editierdistanz (UTF-16 wie Kotlin). Wie Android `PortfolioWalletNames.kt`.
enum PortfolioWalletNames {
    /// Höchstens so viele Vorschläge.
    static let maxSuggestions = 8

    /// Vergleichsform: ohne Akzente (NFD, Zeichen ohne Breite weg), klein, ohne Leerraum.
    static func fold(_ name: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in name.decomposedStringWithCanonicalMapping.unicodeScalars
        where scalar.properties.generalCategory != .nonspacingMark && !scalar.properties.isWhitespace {
            scalars.append(scalar)
        }
        return String(scalars).lowercased()
    }

    /// Bekannte Namen: getrimmt, ohne leere, je Vergleichsform nur die erste Schreibweise (Reihenfolge bleibt —
    /// zuerst die selbst benutzten, dann die Börsen der App).
    static func known(_ names: [String?]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for raw in names {
            let name: String = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty { continue }
            if seen.insert(fold(name)).inserted { out.append(name) }
        }
        return out
    }

    /// Vorschläge als Chips: ohne Eingabe die selbst benutzten Namen `used`; mit Eingabe aus `used` und `catalog`
    /// zuerst «beginnt mit», dann «enthält» (Vergleichsform). Der schon getippte Name selbst nicht.
    static func suggestions(used: [String], catalog: [String], query: String, limit: Int = maxSuggestions) -> [String] {
        let q: String = fold(query.trimmingCharacters(in: .whitespacesAndNewlines))
        if q.isEmpty { return Array(known(used).prefix(limit)) }
        let all: [String] = known(used + catalog).filter { fold($0) != q }
        let starts: [String] = all.filter { fold($0).hasPrefix(q) }
        let contains: [String] = all.filter { (name: String) -> Bool in
            let f = fold(name)
            return !f.hasPrefix(q) && f.contains(q)
        }
        return Array((starts + contains).prefix(limit))
    }

    /// Fast gleicher bekannter Name zu `input` («Binanse» → «Binance», «Bin ance» → «Binance», «Ledgér» → «Ledger»);
    /// nil, wenn leer, schon bekannt (gleiche Wallet: `PortfolioWallets.key`) oder nichts nah genug. Erst gleiche
    /// Vergleichsform, sonst die kleinste Editierdistanz ≤ `maxDistance` (bei Gleichstand der erste in `known`).
    static func nearDuplicate(_ input: String, known: [String]) -> String? {
        let name: String = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return nil }
        let key: String = PortfolioWallets.key(name)
        if known.contains(where: { PortfolioWallets.key($0) == key }) { return nil }
        let f: String = fold(name)
        if f.isEmpty { return nil }
        if let same = known.first(where: { fold($0) == f }) { return same }
        let fLength: Int = f.utf16.count
        var best: String?
        var bestDistance = Int.max
        for candidate in known {
            let c: String = fold(candidate)
            let cLength: Int = c.utf16.count
            if cLength == 0 { continue }
            let limit: Int = maxDistance(Swift.min(fLength, cLength))
            if limit == 0 || abs(fLength - cLength) > limit { continue }
            let d: Int = distance(f, c)
            if d >= 1 && d <= limit && d < bestDistance {
                best = candidate
                bestDistance = d
            }
        }
        return best
    }

    /// Erlaubte Tippfehler je Länge: unter 4 Zeichen keine (sonst «OKX» ≈ «BTC»), bis 5 einer, sonst zwei.
    static func maxDistance(_ length: Int) -> Int {
        if length < 4 { return 0 }
        if length <= 5 { return 1 }
        return 2
    }

    /// Levenshtein-Distanz (Einfügen, Löschen, Ersetzen je 1) über UTF-16-Einheiten wie Kotlin.
    static func distance(_ a: String, _ b: String) -> Int {
        let x: [UInt16] = Array(a.utf16)
        let y: [UInt16] = Array(b.utf16)
        if x == y { return 0 }
        if x.isEmpty { return y.count }
        if y.isEmpty { return x.count }
        var previous: [Int] = Array(0...y.count)
        var current: [Int] = Array(repeating: 0, count: y.count + 1)
        for i in 1...x.count {
            current[0] = i
            for j in 1...y.count {
                let cost: Int = x[i - 1] == y[j - 1] ? 0 : 1
                let deletion: Int = previous[j] + 1
                let insertion: Int = current[j - 1] + 1
                let substitution: Int = previous[j - 1] + cost
                current[j] = Swift.min(deletion, insertion, substitution)
            }
            swap(&previous, &current)
        }
        return previous[y.count]
    }
}
