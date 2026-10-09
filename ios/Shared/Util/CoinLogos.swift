import Foundation

/// Regeln für die Coin-Logos (gleich in Android `CoinLogos.kt`, gemeinsame Testfälle
/// `testdata/parity/coin_logos.json`):
///
/// - Erste Quelle: die eigene Logo-Liste auf GitHub Pages (`indexURL`), täglich von einer GitHub
///   Action gebaut; die folgenden Quellen gelten nur, solange es sie nie gab (TradFi weiter Binance).
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
/// - Dritte Quelle: die offizielle Token-Liste von Binance Alpha (`alphaListURL`) für kleinere
///   Futures-Token ohne CoinGecko-Rang (AIA, AGT, AIO …).
/// - Fehlt eine Quelle, bleibt Bekanntes erhalten und die Liste wird nach einigen Stunden neu geholt.
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
    /// Danach weitere Seiten (Rang 1001–2500) nur für die übrigen Lücken (AIN, LUNA …), erst nach der
    /// Binance- und der Alpha-Liste — wie `EXTRA_PAGES` (Android).
    static let extraPages = 6
    static let perPage = 250

    /// Pause vor jeder weiteren Seite der Rangliste: Ohne Schlüssel erlaubt CoinGecko nur wenige
    /// Abrufe pro Minute; ohne Pause scheiterten Seiten mit «429» und die Liste blieb unvollständig.
    static let pagePauseMillis: Int64 = 6_000
    /// «Zu viele Anfragen» ohne Angabe: so lange warten, dann die Seite einmal wiederholen.
    static let defaultRateLimitWaitMillis: Int64 = 30_000
    static let rateLimitWaitMaxMillis: Int64 = 60_000

    /// Wartezeit nach «429» («Retry-After» in Sekunden), begrenzt auf 1–60 s.
    static func rateLimitWaitMillis(retryAfterSeconds: Int64?) -> Int64 {
        min(max(retryAfterSeconds.map { $0 * 1000 } ?? defaultRateLimitWaitMillis, 1_000), rateLimitWaitMaxMillis)
    }

    /// Unvollständige Liste (eine Quelle fehlte): nach so langer Zeit neu versuchen statt nach einer Woche.
    static let partialTTLMillis: Int64 = 6 * 60 * 60 * 1000

    /// Gespeicherter Zeitpunkt der Liste: vollständig → jetzt, sonst Ablauf nach `partialTTLMillis`.
    static func savedAt(now: Int64, complete: Bool) -> Int64 {
        complete ? now : now - mapTTLMillis + partialTTLMillis
    }

    /// Neue Liste, ergänzt um bisher bekannte Einträge, die diesmal fehlen (eine Quelle war nicht
    /// erreichbar) — ein Teil-Abruf nimmt nie vorhandene Logos oder Namen weg.
    static func withKnown(_ map: inout [String: String], order: inout [String], known: [String: String]) {
        for key in known.keys.sorted() where map[key] == nil {
            map[key] = known[key]
            order.append(key)
        }
    }
    /// Kantenlänge der gespeicherten Bilder in Pixeln.
    static let storedPixels = 128

    /// Symbolliste der Binance-Website (inoffiziell, ohne Schlüssel), je Eintrag `name` und `logo`.
    static let binanceListURL = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"

    // MARK: Eigene Logo-Liste auf GitHub Pages (erste Quelle)

    /// Logo-Liste, die eine GitHub Action täglich baut (`.github/scripts/build_logos.py`): alle Coins
    /// aus CoinGecko (gleiche Kürzel sauber aufgelöst) und OKX, Bilder als WebP ≤ 128 px daneben —
    /// wie `INDEX_URL` (Android).
    static let indexBase = "https://r1adbe.github.io/cryptoChecker/logos/"
    static let indexURL = indexBase + "index.json"
    static let indexImageBase = indexBase + "img/"

    /// «img/» + 16 Kleinbuchstaben-Hex + «.webp».
    static func isIndexFile(_ path: String) -> Bool {
        guard path.hasPrefix("img/"), path.hasSuffix(".webp") else { return false }
        let hex = path.dropFirst(4).dropLast(5)
        return hex.count == 16 && hex.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }

    /// Inhalt der Logo-Liste: Kürzel → Bild-Adresse und Kürzel → Name (Schlüssel wie `normalize`).
    struct Index: Equatable {
        var logos: [String: String]
        var names: [String: String]
    }

    /// `index.json` lesen (`{"version":1,"coins":{"BTC":{"f":"img/….webp","n":"Bitcoin"}}}`).
    /// Ungültige Kürzel und Dateinamen fallen weg; nil, wenn unbrauchbar oder leer.
    static func parseIndex(_ text: String?) -> Index? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let root = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let version = (root["version"] as? NSNumber)?.intValue, version >= 1,
              let coins = root["coins"] as? [String: Any]
        else { return nil }
        var index = Index(logos: [:], names: [:])
        for (k, v) in coins {
            let key = k.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            // Krypto wie `normalize` (kein Hebel-Präfix), TradFi als «TRADFI:NVDA»
            guard fileName(key) != nil, key.hasPrefix(tradFiPrefix) || normalize(key) == key,
                  let o = v as? [String: Any] else { continue }
            if let f = (o["f"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), isIndexFile(f) {
                index.logos[key] = indexBase + f
            }
            if let name = cleanName(o["n"] as? String) { index.names[key] = name }
        }
        return index.logos.isEmpty && index.names.isEmpty ? nil : index
    }

    /// Alle Logos in einer Datei (Anhang der Release «logos»): beim ersten Laden ein Abruf statt Tausender.
    static let packURL = "https://github.com/r1adBE/cryptoChecker/releases/download/logos/logos.pack"
    /// Ab so vielen fehlenden Bildern lieber das ganze Paket.
    static let packMinMissing = 40
    /// Grösstes erlaubtes Paket.
    static let packMaxBytes = 40 * 1024 * 1024
    /// Aktiennamen aller US-Aktien («CAT<Tab>Caterpillar, Inc.» je Zeile, wie `encodeNames`).
    static let stocksURL = indexBase + "stocks.txt"

    /// Paket lesen: «CCLP1\n», dann je Bild «img/<hash>.webp<Tab><Länge>\n» und die Bytes. Pfad → Bytes;
    /// nur gültige Pfade (`isIndexFile`); bei kaputtem Rest bleibt, was bis dahin vollständig war.
    /// nil, wenn es kein Paket ist — wie Android `parsePack`.
    static func parsePack(_ data: Data) -> [String: Data]? {
        let bytes = [UInt8](data)
        let magic = Array("CCLP1\n".utf8)
        guard bytes.count >= magic.count, Array(bytes[0..<magic.count]) == magic else { return nil }
        var out: [String: Data] = [:]
        var i = magic.count
        while i < bytes.count {
            var nl = i
            while nl < bytes.count, bytes[nl] != 0x0A, nl - i < 200 { nl += 1 }
            guard nl < bytes.count, bytes[nl] == 0x0A,
                  let head = String(bytes: bytes[i..<nl], encoding: .ascii) else { break }
            let parts = head.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2, let size = Int(parts[1]) else { break }
            let start = nl + 1
            guard size >= 0, size <= bytes.count - start else { break }
            let path = String(parts[0])
            if isIndexFile(path) { out[path] = Data(bytes[start..<(start + size)]) }
            i = start + size
        }
        return out
    }

    /// Liste aus der eigenen Logo-Liste? (Dann nie auf CoinGecko & Co. zurückfallen.)
    static func isFromIndex(_ map: [String: String]) -> Bool {
        map.values.contains { $0.hasPrefix(indexImageBase) }
    }

    /// Kürzel, deren Bild-Adresse sich geändert hat (in beiden Listen, verschiedene Adresse).
    static func changedKeys(old: [String: String], new: [String: String]) -> Set<String> {
        Set(new.keys.filter { old[$0] != nil && old[$0] != new[$0] })
    }

    /// Offizielle Token-Liste von Binance Alpha (ganze Liste, ohne Schlüssel; `symbol`, `name`,
    /// `iconUrl`): kleinere Token, die Binance als Futures führt (AIA, AGT, AIO …), oft ohne
    /// Marktkapitalisierung bei CoinGecko und daher nicht in deren Rangliste.
    static let alphaListURL = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"

    /// Eintrag der Alpha-Liste.
    struct AlphaEntry: Equatable {
        var symbol: String
        var name: String?
        var icon: String?
        var marketCap: Double? = nil
        var offline: Bool = false
    }

    /// Reihenfolge für `pick`/`pickNames`: handelbare vor abgemeldeten, dann grösste
    /// Marktkapitalisierung (unbekannt zuletzt); sonst Reihenfolge der Liste — wie Android.
    static func rankAlpha(_ entries: [AlphaEntry]) -> [AlphaEntry] {
        func cap(_ e: AlphaEntry) -> Double {
            guard let c = e.marketCap, c.isFinite, c >= 0 else { return -1 }
            return c
        }
        return entries.enumerated().sorted { a, b in
            let oa = a.element.offline ? 1 : 0, ob = b.element.offline ? 1 : 0
            if oa != ob { return oa < ob }
            let ca = cap(a.element), cb = cap(b.element)
            if ca != cb { return ca > cb }
            return a.offset < b.offset
        }.map(\.element)
    }

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
        guard let url else { return false }
        // Eigene Logo-Liste auf GitHub Pages: nur «img/<16 Hex>.webp»
        if url.hasPrefix(indexImageBase) { return isIndexFile(String(url.dropFirst(indexBase.count))) }
        guard url.hasPrefix("https://") else { return false }
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
