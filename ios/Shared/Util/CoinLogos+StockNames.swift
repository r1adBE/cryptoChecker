import Foundation

// Aktiennamen aus der Nasdaq-Symbolliste (Teil von `CoinLogos`) — wie `CoinLogosStockNames.kt` (Android).

extension CoinLogos {
    /// Offizielle Symbolliste von Nasdaq (Nasdaq-Aktien) und der übrigen US-Börsen (NYSE u. a.).
    static let nasdaqListedURL = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt"
    static let otherListedURL = "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"

    private static let stockNameTail = #"[\s,]*(?:New\s+)?(?:(?:Class|Series)\s+[A-Z0-9]+\s+)?(?:Common Stock|Common Shares|Ordinary Shares|American Deposit[ao]ry Shares|Depositary Shares|Shares of Beneficial Interest)\b.*$"#

    /// Firmenname ohne Wertpapier-Zusatz: «Caterpillar, Inc. Common Stock» → «Caterpillar, Inc.»,
    /// «Apple Inc. - Common Stock» → «Apple Inc.»; danach wie `cleanName` — wie `cleanStockName` (Android).
    static func cleanStockName(_ raw: String?) -> String? {
        guard let raw else { return nil }
        var s = raw.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        if let dash = s.range(of: " - "), dash.lowerBound > s.startIndex {
            s = String(s[..<dash.lowerBound])
        }
        if let range = s.range(of: stockNameTail, options: [.regularExpression, .caseInsensitive]) {
            s.removeSubrange(range)
        }
        s = s.trimmingCharacters(in: CharacterSet(charactersIn: ", ").union(.whitespaces))
        return cleanName(s)
    }

    /// Eine Datei der Nasdaq-Symbolliste → Kürzel → Name; Test-Einträge und die Schlusszeile fallen
    /// weg, erstes Vorkommen gewinnt — wie `parseSymbolDirectory` (Android).
    static func parseSymbolDirectory(_ text: String?, into out: inout [String: String]) {
        guard let text else { return }
        var lines = text.split(whereSeparator: \.isNewline).makeIterator()
        guard let headerLine = lines.next() else { return }
        let header = headerLine.split(separator: "|", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        guard let sym = header.firstIndex(where: { $0 == "Symbol" || $0 == "ACT Symbol" }),
              let name = header.firstIndex(of: "Security Name") else { return }
        let test = header.firstIndex(of: "Test Issue")
        while let line = lines.next() {
            let parts = line.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.count > max(sym, name) else { continue }
            if let test, test < parts.count, parts[test].trimmingCharacters(in: .whitespaces) == "Y" { continue }
            let symbol = parts[sym].trimmingCharacters(in: .whitespaces).uppercased()
            guard !symbol.isEmpty, out[symbol] == nil, let clean = cleanStockName(parts[name]) else { continue }
            out[symbol] = clean
        }
    }

    /// Namen der TradFi-Kürzel aus der Symbolliste (Schlüssel `tradFiKey`), nur wo `names` noch
    /// keinen hat — wie `withStockNames` (Android).
    static func withStockNames(_ names: [String: String], stockNames: [String: String], tradFiBases: Set<String>) -> [String: String] {
        var out = names
        for base in tradFiBases.sorted() {
            let key = tradFiKey(base)
            guard out[key] == nil,
                  let name = stockNames[base.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()] else { continue }
            out[key] = name
        }
        return out
    }
}
