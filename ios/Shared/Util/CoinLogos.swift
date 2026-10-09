import Foundation

/// Regeln für die Coin-Logos (gleich in Android `CoinLogos.kt`, gemeinsame Testfälle
/// `testdata/parity/coin_logos.json`):
///
/// - Zuordnung Symbol → Bild-Adresse aus der öffentlichen CoinGecko-Rangliste (`/coins/markets`,
///   Feld `image`), nach Marktkapitalisierung sortiert. Teilen sich Coins ein Symbol, gewinnt der
///   grösste (der erste in der Rangliste).
/// - Nichts mitgeliefert, aber auch nichts einzeln: Die App lädt die Logos **aller** Coins der
///   Rangliste auf einmal (danach nur neu dazugekommene) und zeigt sie nur aus dem Speicher auf
///   dem Gerät. So sieht CoinGecko bei jedem Nutzer dieselben Abrufe und erfährt nie, welche Coins
///   in einer Merkliste stehen. Coins ausserhalb der Rangliste bekommen Initialen.
/// - Zweite Quelle für Lücken: die öffentliche Symbolliste der Binance-Website (`binanceListURL`,
///   Feld `logo`) — vor allem TradFi (Gold, Silber, Aktien), die CoinGecko nicht führt. Sie füllt
///   nur Symbole, die CoinGecko nicht kennt (`pick` lässt Vorhandenes stehen). Inoffizielle
///   Schnittstelle: Fällt sie weg, bleiben dort Initialen.
/// - TradFi-Paare (Aktien, Rohstoffe, Devisen; Kennzeichen der Paarliste) nie aus CoinGecko: Dort
///   heisst oft ein fremder Token gleich («CAT», «NVDA»). Ihr Logo kommt nur aus der Binance-Liste
///   (`pickTradFi`, Schlüssel `tradFiKey`); ohne Treffer Initialen.
/// - Nur Bilder von CoinGecko- und Binance-Bildservern über HTTPS (`isAllowedURL`).
/// - Kein Logo (unbekannt, Fehler, Schalter aus): Kreis mit `initials` — nie ein kaputtes Bild.
enum CoinLogos {
    /// Zuordnung eine Woche lang gültig.
    static let mapTTLMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    /// Fehlgeschlagenes Bild erst nach einem Tag erneut versuchen.
    static let failureTTLMillis: Int64 = 24 * 60 * 60 * 1000
    /// Seiten à 250 Coins: die grössten 1000 zuerst (vor der Binance-Liste).
    static let pages = 4
    /// Danach weitere Seiten (Rang 1001–2500) nur für die übrigen Lücken (AIN, AGT, AIA, LUNA …), erst
    /// nach der Binance-Liste und mit Pause zwischen den Seiten — wie `EXTRA_PAGES` (Android).
    static let extraPages = 6
    static let extraPagePauseNanos: UInt64 = 2_000_000_000
    static let perPage = 250
    /// Kantenlänge der gespeicherten Bilder in Pixeln.
    static let storedPixels = 128

    /// Symbolliste der Binance-Website (inoffiziell, ohne Schlüssel), je Eintrag `name` und `logo`.
    static let binanceListURL = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"

    static func marketsURL(page: Int) -> String {
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc"
            + "&per_page=\(perPage)&page=\(page)&sparkline=false"
    }

    private static let multiplierPrefixes = ["1000000", "100000", "10000", "1000", "1M"]
    // «LUNA2»: Terra 2.0 heisst bei den Futures-Börsen so (LUNA2USDT) — wie Android
    private static let aliases = ["XBT": "BTC", "XDG": "DOGE", "LUNA2": "LUNA"]

    /// Symbol, unter dem das Logo gesucht wird: gross, ohne Leerraum, ohne Hebel-Präfix
    /// («1000PEPE» → «PEPE»), Börsen-Kürzel vereinheitlicht («XBT» → «BTC»).
    static func normalize(_ symbol: String) -> String {
        var s = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        for prefix in multiplierPrefixes where s.hasPrefix(prefix) {
            let rest = s.dropFirst(prefix.count)
            if rest.count >= 2, let first = rest.first, first.isLetter {
                s = String(rest)
                break
            }
        }
        return aliases[s] ?? s
    }

    /// Initialen für den Ersatz-Kreis: bis vier Zeichen ganz, sonst die ersten drei.
    static func initials(_ symbol: String) -> String {
        let s = displaySymbol(symbol).trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return s.count <= 4 ? s : String(s.prefix(3))
    }

    /// Börse, deren Symbole jeder frei wählen kann (DEX-Pools).
    static let dexMarketKey = "DexScreener"

    /// Logo für Paare dieser Börse? Bei DEX-Pools nie: Ein fremder Token darf «BTC» heissen —
    /// dann nie das Bitcoin-Logo daneben, nur Initialen.
    static func allowed(forMarket marketKey: String?) -> Bool { marketKey != dexMarketKey }

    /// Nur HTTPS-Bilder von CoinGecko (`coin-images.coingecko.com` u. ä.) und Binance (`bin.bnbstatic.com`).
    static func isAllowedURL(_ url: String?) -> Bool {
        guard let url, url.hasPrefix("https://") else { return false }
        let rest = url.dropFirst("https://".count)
        let host = String(rest.prefix { $0 != "/" && $0 != "?" }).lowercased()
        if host.isEmpty || host.contains("@") || host.contains(":") { return false }
        guard host == "coingecko.com" || host.hasSuffix(".coingecko.com") || host.hasSuffix(".bnbstatic.com")
        else { return false }
        // Platzhalter für Coins ohne Logo («missing_large.png»): lieber Initialen
        return !url.contains("/missing_")
    }

    /// Kleine Fassung des Bildes (CoinGecko liefert «thumb», «small», «large»; die Rangliste nennt
    /// «large»). «small» reicht für 24–48 pt und spart beim Laden aller Logos viel Datenvolumen.
    static func smallURL(_ url: String) -> String {
        guard let range = url.range(of: "/large/") else { return url }
        return url.replacingCharacters(in: range, with: "/small/")
    }

    /// Rangliste (Reihenfolge = Marktkapitalisierung) → Symbol → Adresse; erstes Vorkommen gewinnt.
    /// `order` hält die Reihenfolge fest (für Tests und den Zwischenspeicher).
    static func pick(_ ranked: [(symbol: String, image: String?)],
                     into map: inout [String: String], order: inout [String]) {
        for (symbol, url) in ranked {
            let key = normalize(symbol)
            guard !key.isEmpty, map[key] == nil, isAllowedURL(url), let url else { continue }
            map[key] = url.trimmingCharacters(in: .whitespacesAndNewlines)
            order.append(key)
        }
    }

    // MARK: TradFi (Aktien, Rohstoffe, Devisen)

    /// Börsen, deren Paarliste TradFi kennzeichnet (wie `PairCache` und Android).
    static let tradFiMarkets: Set<String> = ["BinanceFutures", "BybitFutures", "OkexFutures", "MexcFutures", "BitgetFutures"]

    /// Vorsilbe der Schlüssel für TradFi-Logos (eigener Namensraum, nie ein Krypto-Logo).
    static let tradFiPrefix = "TRADFI:"

    /// Schlüssel des Logos für ein TradFi-Paar: «TRADFI:NVDA».
    /// Aktien-Logo auf dem Bild-Server von Binance («…/static/stock/BYD.png») für ein TradFi-Kürzel;
    /// nil, wenn das Kürzel nicht passt (nur A–Z, 0–9, Punkt, höchstens 12) — wie `stockLogoUrl` (Android).
    /// Geladen für **alle** TradFi-Kürzel der gespeicherten Paarlisten, nur ohne Logo aus der Binance-Liste.
    static func stockLogoURL(_ symbol: String) -> String? {
        let s = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !s.isEmpty, s.count <= 12,
              s.unicodeScalars.allSatisfy({ ("A"..."Z").contains($0) || ("0"..."9").contains($0) || $0 == "." })
        else { return nil }
        return "https://bin.bnbstatic.com/static/stock/\(s).png"
    }

    /// Zuordnung samt Aktien-Logos: für jedes Kürzel aus `tradFiBases` ohne eigenen Eintrag
    /// (`tradFiKey`) das Logo aus `stockLogoURL` — wie `withStockLogos` (Android).
    static func withStockLogos(_ map: [String: String], tradFiBases: Set<String>) -> [String: String] {
        var out = map
        for base in tradFiBases.sorted() {
            let key = tradFiKey(base)
            guard out[key] == nil, fileName(key) != nil, let url = stockLogoURL(base) else { continue }
            out[key] = url
        }
        return out
    }

    static func tradFiKey(_ symbol: String) -> String {
        tradFiPrefix + symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Schlüssel für die Plakette: TradFi-Paare im eigenen Namensraum, sonst das Symbol.
    static func logoKey(_ symbol: String, tradFi: Bool) -> String { tradFi ? tradFiKey(symbol) : symbol }

    /// Symbol zum Anzeigen (Initialen) ohne `tradFiPrefix`.
    static func displaySymbol(_ key: String) -> String {
        key.hasPrefix(tradFiPrefix) ? String(key.dropFirst(tradFiPrefix.count)) : key
    }

    /// Kennung eines Paars für die Liste der TradFi-Paare: «BinanceFutures|NVDA|USDT|PERPETUAL».
    static func pairKey(marketKey: String, base: String, quote: String, contractType: String) -> String {
        "\(marketKey)|\(base.uppercased())|\(quote.uppercased())|\(contractType)"
    }

    /// Eintrag der Binance-Symbolliste: Kürzel, Logo, Schlagwörter («bStocks»), nur als Futures, voller Name.
    struct BinanceEntry {
        var name: String
        var logo: String?
        var tags: [String] = []
        var onlyFutures = false
        var fullName: String? = nil
    }

    /// Schlagwort der tokenisierten Aktien in der Binance-Liste.
    static let stockTag = "bStocks"

    /// TradFi-Logos aus der Binance-Liste, Schlüssel `tradFiKey`; vorhandene bleiben — wie Android:
    ///  1. tokenisierte Aktie («bStocks», «NVDAB» → NVDA), 2. gleichnamiger Eintrag nur als Futures,
    ///  3. gleichnamiger Eintrag, den CoinGecko nicht kennt (`crypto`), ohne Hebel-Präfix.
    static func pickTradFi(_ entries: [BinanceEntry], crypto: Set<String>,
                           into map: inout [String: String], order: inout [String]) {
        pickTradFi(entries, crypto: crypto, into: &map, order: &order) { e in
            guard let logo = e.logo, isAllowedURL(logo) else { return nil }
            return logo.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    /// Namen der TradFi-Paare in derselben Reihenfolge wie `pickTradFi` («NVDAB» → «NVIDIA»).
    static func pickTradFiNames(_ entries: [BinanceEntry], crypto: Set<String>,
                                into map: inout [String: String], order: inout [String]) {
        pickTradFi(entries, crypto: crypto, into: &map, order: &order) { cleanName($0.fullName, symbol: $0.name) }
    }

    private static func pickTradFi(_ entries: [BinanceEntry], crypto: Set<String>,
                                   into map: inout [String: String], order: inout [String],
                                   value: (BinanceEntry) -> String?) {
        func put(_ symbol: String, _ e: BinanceEntry) {
            let key = tradFiKey(symbol)
            guard fileName(key) != nil, map[key] == nil, let v = value(e) else { return }
            map[key] = v
            order.append(key)
        }
        func upper(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
        for e in entries {
            let name = upper(e.name)
            if name.count > 1, name.hasSuffix("B"), e.tags.contains(where: { $0.caseInsensitiveCompare(stockTag) == .orderedSame }) {
                put(String(name.dropLast()), e)
            }
        }
        for e in entries where e.onlyFutures { put(e.name, e) }
        for e in entries {
            let name = upper(e.name)
            // Ohne Hebel-Präfix («1000CAT» ist ein Krypto-Token)
            if !crypto.contains(name), normalize(name) == name { put(name, e) }
        }
    }

    // MARK: Namen («Bitcoin», «NVIDIA») aus denselben Listen

    /// Höchstlänge eines Namens in der Merkliste.
    static let nameMax = 40

    /// Name zum Anzeigen: ohne Zusatz der tokenisierten Aktien («NVIDIA bStocks» → «NVIDIA»), ohne
    /// Tabs und Zeilenumbrüche, gekürzt auf `nameMax`. nil, wenn leer. Ein Name gleich dem Kürzel bleibt
    /// («BNB», «XRP», «Bonk» zu BONK) — besser als «–»; wie Android.
    static func cleanName(_ raw: String?, symbol: String? = nil) -> String? {
        guard let raw else { return nil }
        var s = raw.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        if let range = s.range(of: #"[\s,(]*(bStocks?|xStocks?|Tokeni[sz]ed Stock)\)?\s*$"#,
                               options: [.regularExpression, .caseInsensitive]) {
            s.removeSubrange(range)
        }
        s = s.trimmingCharacters(in: .whitespaces)
        guard !s.isEmpty else { return nil }
        if s.count > nameMax {
            return String(s.prefix(nameMax - 1)).trimmingCharacters(in: .whitespaces) + "…"
        }
        return s
    }

    /// Rangliste → Symbol → Name; erstes Vorkommen gewinnt (wie `pick`).
    static func pickNames(_ ranked: [(symbol: String, name: String?)],
                          into map: inout [String: String], order: inout [String]) {
        for (symbol, name) in ranked {
            let key = normalize(symbol)
            guard !key.isEmpty, map[key] == nil, let clean = cleanName(name, symbol: symbol) else { continue }
            map[key] = clean
            order.append(key)
        }
    }

    // MARK: Aktiennamen aus der Nasdaq-Symbolliste — wie Android

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

    /// Namen-Zwischenspeicher: eine Zeile je Coin, «BTC\tBitcoin».
    static func encodeNames(_ map: [String: String], order: [String]) -> String {
        order.compactMap { key in map[key].map { "\(key)\t\($0)" } }.joined(separator: "\n")
    }

    static func decodeNames(_ text: String?) -> [String: String] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [:] }
        var out: [String: String] = [:]
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let parts = line.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2 else { continue }
            let key = parts[0].trimmingCharacters(in: .whitespaces)
            if !key.isEmpty, out[key] == nil, let name = cleanName(String(parts[1])) { out[key] = name }
        }
        return out
    }

    /// Schlüssel eines Namens: wie das Logo, Krypto-Symbole vereinheitlicht (`normalize`).
    static func nameKey(_ symbol: String, tradFi: Bool) -> String { tradFi ? tradFiKey(symbol) : normalize(symbol) }

    // MARK: Zwischenspeicher: eine Zeile je Coin, «BTC\thttps://…»

    static func encode(_ map: [String: String], order: [String]) -> String {
        order.compactMap { key in map[key].map { "\(key)\t\($0)" } }.joined(separator: "\n")
    }

    static func decode(_ text: String?) -> [String: String] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [:] }
        var out: [String: String] = [:]
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let parts = line.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2 else { continue }
            let symbol = parts[0].trimmingCharacters(in: .whitespaces)
            let url = parts[1].trimmingCharacters(in: .whitespaces)
            if !symbol.isEmpty, isAllowedURL(url), out[symbol] == nil { out[symbol] = url }
        }
        return out
    }

    static func isFresh(savedAt: Int64, now: Int64, ttl: Int64 = mapTTLMillis) -> Bool {
        savedAt >= 1 && savedAt <= now && now - savedAt < ttl
    }

    /// Dateiname im Bild-Zwischenspeicher; nur Symbole aus A–Z und 0–9 (sonst nil → Initialen);
    /// TradFi-Logos als «T_NVDA.png».
    static func fileName(_ symbol: String) -> String? {
        if symbol.hasPrefix(tradFiPrefix) {
            let raw = displaySymbol(symbol).trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard !raw.isEmpty, isPlain(raw) else { return nil }
            return "T_" + raw + ".png"
        }
        let key = normalize(symbol)
        guard !key.isEmpty, isPlain(key) else { return nil }
        return key + ".png"
    }

    private static func isPlain(_ s: String) -> Bool {
        s.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
    }
}

/// Wo Logos gezeigt werden (Schalter unter Darstellung › Coin-Logos), wie Android `CoinLogoUse`.
enum CoinLogoUse {
    /// Portfolio-Logos zählen nur mit eingeschaltetem Portfolio-Tab.
    static func portfolio(_ s: AppSettings) -> Bool { s.portfolioCoinLogos && s.portfolioEnabled }

    static func needed(_ s: AppSettings) -> Bool { s.coinLogos || s.widgetCoinLogos || portfolio(s) }
}
