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
/// - Dritte Quelle: die offizielle Token-Liste von Binance Alpha (`alphaListURL`) für kleinere
///   Futures-Token ohne CoinGecko-Rang (AIA, AGT, AIO …).
/// - Fehlt eine Quelle, bleibt Bekanntes erhalten und die Liste wird nach einigen Stunden neu geholt.
/// - TradFi-Paare (Aktien, Rohstoffe, Devisen; Kennzeichen der Paarliste) nie aus CoinGecko: Dort
///   heisst oft ein fremder Token gleich («CAT», «NVDA»). Ihr Logo kommt nur aus der Binance-Liste
///   (`pickTradFi`, Schlüssel `tradFiKey`); ohne Treffer Initialen.
/// - Nur Bilder von CoinGecko- und Binance-Bildservern über HTTPS (`isAllowedURL`). Kein eigenes
///   Logo-Paket (CoinGecko-API-Bedingungen verbieten Weitergabe und Speicherung ihrer Daten).
/// - Mitgeliefert (Build 46): das freie Icon-Paket «Cryptocurrency Icons» (CC0, `iconPackFile`, rund
///   480 grössere Coins) — sofort und ohne Netz; Reihenfolge: CoinGecko → Binance-Liste/Alpha →
///   mitgeliefertes Icon → Initialen. Kein Abruf bei jsDelivr mehr.
/// - Kein Logo (unbekannt, Fehler, Schalter aus): Kreis mit `initials` — nie ein kaputtes Bild.
enum CoinLogos {
    /// Zuordnung (Rangliste/Namen von CoinGecko) höchstens 24 h gültig — CoinGecko-Bedingungen:
    /// Daten höchstens 24 h zwischenspeichern. Schon geladene Bilder bleiben im Zwischenspeicher.
    static let mapTTLMillis: Int64 = 24 * 60 * 60 * 1000
    /// Aktiennamen (Nasdaq-Symbolliste, nicht CoinGecko) weiterhin eine Woche lang gültig.
    static let stockNamesTTLMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    /// Fehlgeschlagenes Bild erst nach einem Tag erneut versuchen.
    static let failureTTLMillis: Int64 = 24 * 60 * 60 * 1000
    /// Seiten à 250 Coins: die grössten 1000 zuerst (vor der Binance-Liste).
    static let pages = 4
    /// Danach weitere Seiten (Rang 1001–2500) nur für die übrigen Lücken (AIN, LUNA …), erst nach der
    /// Binance- und der Alpha-Liste — wie `EXTRA_PAGES` (Android). Nur auf nicht teuren Netzen (WLAN,
    /// Ethernet; `NWPath.isExpensive`/`isConstrained` false): Auf mobilen Daten fehlen die Zusatzseiten, und
    /// die Logos der Kürzel, die nur sie bringen, werden nicht geladen (`downloadable`) — die grössten 1000
    /// sowie die Binance- und Alpha-Liste laden wie bisher.
    static let extraPages = 6
    static let perPage = 250

    /// Logos für den Sammel-Abruf: auf teuren Netzen (`unmetered` false) ohne die Kürzel, die nur die
    /// Zusatzseiten (`extraPages`) bringen (`extraOnly`); sonst alle aus `wanted` — wie Android.
    static func downloadable(_ wanted: [String: String], extraOnly: Set<String>, unmetered: Bool) -> [String: String] {
        if unmetered || extraOnly.isEmpty { return wanted }
        return wanted.filter { !extraOnly.contains($0.key) }
    }

    /// Kürzel, die erst die Zusatzseiten in die Zuordnung bringen (`all` ohne die Kürzel von `top`).
    static func extraOnlyKeys(all: [String: String], top: [String: String]) -> Set<String> {
        Set(all.keys).subtracting(top.keys)
    }

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

    /// Unvollständige Liste (eine Quelle fehlte): nach so langer Zeit neu versuchen statt nach 24 h.
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

    /// Kürzel, deren Bild-Adresse sich geändert hat (in beiden Listen, verschiedene Adresse).
    static func changedKeys(old: [String: String], new: [String: String]) -> Set<String> {
        Set(new.keys.filter { old[$0] != nil && old[$0] != new[$0] })
    }

    /// Offizielle Token-Liste von Binance Alpha (ganze Liste, ohne Schlüssel; `symbol`, `name`,
    /// `iconUrl`): kleinere Token, die Binance als Futures führt (AIA, AGT, AIO …), oft ohne
    /// Marktkapitalisierung bei CoinGecko und daher nicht in deren Rangliste.
    static let alphaListURL = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"

    /// Eintrag der Alpha-Liste; `listingCex` = auch regulär an der Börse Binance gelistet (Feld `listingCex`).
    struct AlphaEntry: Equatable {
        var symbol: String
        var name: String?
        var icon: String?
        var marketCap: Double? = nil
        var offline: Bool = false
        var listingCex: Bool = false
        /// Kennung für Kurse und Kerzen («ALPHA_175», `BinanceAlpha`; nil = unbekannt).
        var alphaId: String? = nil
    }

    /// Kürzel der Alpha-Token (`AssetKind.ALPHA`): handelbare Einträge der Alpha-Liste (nicht
    /// `offline`), die Binance nicht regulär listet (`listingCex`), wie `normalize` — wie Android.
    static func alphaSymbols(_ entries: [AlphaEntry]) -> Set<String> {
        var out = Set<String>()
        for e in entries where !e.offline && !e.listingCex {
            let key = normalize(e.symbol)
            if !key.isEmpty, fileName(key) != nil { out.insert(key) }
        }
        return out
    }

    /// Nur die Alpha-Liste holen (ohne Rangliste, ohne Bilder)? Wenn alle Logo-Schalter (`logos`) und
    /// «Namen anzeigen» (`names`) aus sind, «inkl. Alpha-Tokens» (`includeAlpha`) aber an — wie Android.
    static func alphaOnly(logos: Bool, names: Bool, includeAlpha: Bool) -> Bool {
        !logos && !names && includeAlpha
    }

    /// Alpha-Liste allein nicht erreichbar (Server): frühestens nach einer Stunde erneut.
    static let alphaRetryMillis: Int64 = 60 * 60 * 1000

    /// Alpha-Liste allein fällig (`alphaOnly`)? Nein, solange sie gespeichert (`hasSymbols`) und höchstens
    /// einen Tag alt ist (`savedAt`, wie `mapTTLMillis`) oder der letzte Versuch (`attemptAt`) weniger als
    /// `alphaRetryMillis` zurückliegt — wie Android `alphaListDue`.
    static func alphaListDue(savedAt: Int64, attemptAt: Int64, now: Int64, hasSymbols: Bool) -> Bool {
        !(hasSymbols && isFresh(savedAt: savedAt, now: now)) && !isFresh(savedAt: attemptAt, now: now, ttl: alphaRetryMillis)
    }

    /// Token für die Alpha-Paare von Binance (`BinanceAlpha.pairs`) und «gleicher Token» (`alphaNames`):
    /// handelbare, nicht regulär gelistete Einträge (wie `alphaSymbols`) in der Reihenfolge von `entries`
    /// (`rankAlpha`: bei gleichem Kürzel der bekanntere zuerst) — wie Android.
    static func alphaTokens(_ entries: [AlphaEntry]) -> [BinanceAlpha.Token] {
        entries.filter { !$0.offline && !$0.listingCex }
            .map { BinanceAlpha.Token(symbol: $0.symbol, name: $0.name, alphaId: $0.alphaId) }
    }

    /// Neue Alpha-Liste ohne Kennungen (Ersatzdatei `coin_meta.json` führt kein `alphaId`): Kennungen der
    /// bisherigen Liste `saved` je Kürzel übernehmen, damit die Alpha-Paare nicht bis zum nächsten
    /// direkten Abruf verschwinden. Einträge mit eigener Kennung bleiben unverändert — wie Android.
    static func keepAlphaIds(_ fresh: [AlphaEntry], saved: [AlphaEntry]) -> [AlphaEntry] {
        var ids: [String: String] = [:]
        for e in saved { if let id = e.alphaId, ids[e.symbol] == nil { ids[e.symbol] = id } }
        if ids.isEmpty { return fresh }
        return fresh.map { e in
            guard e.alphaId == nil, let id = ids[e.symbol] else { return e }
            var copy = e
            copy.alphaId = id
            return copy
        }
    }

    /// Hat die Liste Kennungen für die Alpha-Paare (sonst einmal direkt neu holen, z. B. nach dem Update)?
    static func hasAlphaIds(_ entries: [AlphaEntry]?) -> Bool {
        entries?.contains { $0.alphaId != nil } ?? false
    }

    /// Namen der Alpha-Token je Kürzel (wie `normalize`); bei gleichem Kürzel gilt der erste.
    static func alphaNames(_ tokens: [BinanceAlpha.Token]) -> [String: String] {
        var out: [String: String] = [:]
        for t in tokens {
            guard let name = t.name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { continue }
            let key = normalize(t.symbol)
            if !key.isEmpty && out[key] == nil { out[key] = name }
        }
        return out
    }

    /// Logos und Namen der Alpha-Liste (`entries` in `rankAlpha`-Reihenfolge) füllen nur Lücken
    /// (Vorhandenes von CoinGecko und Binance bleibt, wie `pick`); Ergebnis: die Alpha-Kürzel
    /// (`alphaSymbols`). Der Abgleich holt die Alpha-Liste zuerst und speichert die Kürzel sofort,
    /// wendet die Logos aber erst nach CoinGecko und Binance an — wie Android `applyAlpha`.
    @discardableResult
    static func applyAlpha(_ entries: [AlphaEntry],
                           into map: inout [String: String], order: inout [String],
                           names: inout [String: String], nameOrder: inout [String]) -> Set<String> {
        pick(entries.map { (symbol: $0.symbol, image: $0.icon) }, into: &map, order: &order)
        pickNames(entries.map { (symbol: $0.symbol, name: $0.name) }, into: &names, order: &nameOrder)
        return alphaSymbols(entries)
    }

    /// Antwort der Alpha-Liste (`alphaListURL`, `{"code":"000000","data":[…]}`) → Einträge in
    /// `rankAlpha`-Reihenfolge; nil = unbrauchbar (kein `data`-Array). Kürzel über `cleanSymbol`,
    /// `marketCap` als Text oder Zahl, `iconUrl` auch relativ (`alphaIconURL`); Einträge ohne Kürzel
    /// fallen weg, ein kaputter Eintrag kostet nie die ganze Liste — wie Android `parseAlpha`.
    static func parseAlpha(_ text: String?) -> [AlphaEntry]? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let array = object["data"] as? [Any]
        else { return nil }
        return alphaEntries(array)
    }

    /// Einträge eines Alpha-`data`-Arrays (auch aus `coin_meta.json`, `CoinMeta`) wie `parseAlpha`.
    static func alphaEntries(_ array: [Any]) -> [AlphaEntry] {
        var out: [AlphaEntry] = []
        for item in array {
            guard let entry = item as? [String: Any] else { continue }
            let symbol = cleanSymbol(entry["symbol"] as? String)
            if symbol.isEmpty { continue }
            out.append(AlphaEntry(symbol: symbol,
                                  name: entry["name"] as? String,
                                  icon: alphaIconURL(entry["iconUrl"] as? String),
                                  marketCap: jsonNumber(entry["marketCap"]),
                                  offline: jsonBool(entry["offline"]),
                                  listingCex: jsonBool(entry["listingCex"]),
                                  alphaId: BinanceAlpha.alphaId(entry["alphaId"] as? String)))
        }
        return rankAlpha(out)
    }

    /// Kürzel aus einem Token-Vertrag: ohne Leerraum (auch geschütztes Leerzeichen) und ohne
    /// unsichtbare Formatzeichen (z. B. Zero-Width-Space) — wie Android `cleanSymbol`.
    static func cleanSymbol(_ raw: String?) -> String {
        var out = String.UnicodeScalarView()
        for scalar in (raw ?? "").unicodeScalars
        where !scalar.properties.isWhitespace && scalar.properties.generalCategory != .format {
            out.append(scalar)
        }
        return String(out)
    }

    /// Bild-Adresse der Alpha-Liste: relativ («/images/…») → Bild-Server von Binance; sonst unverändert.
    static func alphaIconURL(_ raw: String?) -> String? {
        let s = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        return s.hasPrefix("/") && !s.hasPrefix("//") ? "https://bin.bnbstatic.com" + s : s
    }

    /// Wahrheitswert wie `optBoolean` (Android): JSON-true/false oder Text «true»/«false», sonst false.
    private static func jsonBool(_ value: Any?) -> Bool {
        if let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        if let s = value as? String { return s.lowercased() == "true" }
        return false
    }

    /// Zahl aus Text oder JSON-Zahl (nicht true/false); sonst nil.
    private static func jsonNumber(_ value: Any?) -> Double? {
        if let s = value as? String { return Double(s.trimmingCharacters(in: .whitespacesAndNewlines)) }
        if let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.doubleValue }
        return nil
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
    /// Geprüft (Parity-Fälle): Auch «1000SATS» und «1000CAT» sind bei Binance Hebel-Kürzel
    /// (1000 × SATS (Ordinals) bzw. 1000 × Simon's Cat, CoinGecko-Kürzel SATS bzw. CAT) — die
    /// Zuordnung zu SATS/CAT ist richtig. Ein echter Token «1000…» ohne Hebel ist nicht bekannt;
    /// nach dem Präfix müssen mindestens zwei Zeichen mit Buchstabe vorne folgen («1000X» bleibt).
    /// Dieselbe Regel nutzt `SpotMarket.lookupSymbol` — wie Android.
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
    /// Adressen der früheren eigenen Logo-Liste (GitHub Pages) gelten nicht mehr.
    static func isAllowedURL(_ url: String?) -> Bool {
        guard let url else { return false }
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

    /// Börsen, deren Paarliste TradFi kennzeichnet (wie `PairCache` und Android). HTX/Phemex Futures kamen
    /// später dazu: ihre gespeicherten Listen tragen das Kennzeichen erst nach dem nächsten Laden der Paarliste.
    static let tradFiMarkets: Set<String> = [
        "BinanceFutures", "BybitFutures", "OkexFutures", "MexcFutures", "BitgetFutures", "HtxFutures", "PhemexFutures",
    ]

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

    // MARK: Mitgelieferte Icons: «Cryptocurrency Icons» 0.18.1 (CC0 1.0, github.com/spothq/cryptocurrency-icons)

    /// Mitgeliefertes Icon-Paket: die 64-px-PNGs (`32@2x/color`) der rund 480 grösseren Coins in einer
    /// Datei (`Shared/Resources`, App und Widgets; Android `assets/`; erzeugt mit
    /// `android/tools/gen_coin_assets.py`). Vorrang haben immer die geladenen Logos der Listen.
    static let iconPackName = "coin_icons"
    static let iconPackExtension = "bin"
    private static let iconPackMagic = "CCIP1"
    /// Höchstgrösse des Inhaltsverzeichnisses (Schutz gegen kaputte Dateien).
    private static let iconPackHeaderMax = 64 * 1024

    /// Stelle eines Icons im Paket: Anfang und Länge in Bytes.
    struct IconSlice: Equatable {
        let offset: Int
        let length: Int
    }

    /// Name eines Icons im Paket («btc»); nil bei TradFi oder ungültigem Kürzel — wie Android.
    static func bundledIconName(_ symbol: String) -> String? {
        if symbol.hasPrefix(tradFiPrefix) { return nil }
        let key = normalize(symbol)
        guard fileName(key) != nil else { return nil }
        return key.lowercased()
    }

    /// Inhaltsverzeichnis des Icon-Pakets: Zeile «CCIP1», je Icon eine Zeile «name länge», eine
    /// Leerzeile, dann die PNG-Bytes in derselben Reihenfolge. Ergebnis: Name → `IconSlice` (ab
    /// Anfang von `bytes`); nil = kein Paket oder kaputt — wie Android `parseIconPack`.
    static func parseIconPack(_ bytes: [UInt8]) -> [String: IconSlice]? {
        let limit = min(bytes.count, iconPackHeaderMax)
        var end = -1
        var i = 0
        while i + 1 < limit {
            if bytes[i] == 10 && bytes[i + 1] == 10 {
                end = i
                break
            }
            i += 1
        }
        guard end >= 0 else { return nil }
        let header = String(decoding: bytes[0..<end], as: UTF8.self)
        let lines = header.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.first == iconPackMagic else { return nil }
        var out: [String: IconSlice] = [:]
        var offset = end + 2
        for line in lines.dropFirst() {
            let parts = line.split(separator: " ", omittingEmptySubsequences: false)
            guard parts.count == 2, let length = Int(parts[1]), length > 0 else { return nil }
            let name = String(parts[0])
            guard !name.isEmpty, name.unicodeScalars.allSatisfy({ isIconNameScalar($0) }) else { return nil }
            guard length <= bytes.count - offset else { return nil }
            if out[name] == nil { out[name] = IconSlice(offset: offset, length: length) }
            offset += length
        }
        return out
    }

    /// Erlaubtes Zeichen im Namen eines Icons: a–z, 0–9.
    private static func isIconNameScalar(_ c: Unicode.Scalar) -> Bool {
        let v: UInt32 = c.value
        return (97...122).contains(v) || (48...57).contains(v)
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

/// Nachholen des Logo-Abgleichs nach einem Netzfehler — gleiche Regeln wie Android `CoinLogoRetry`.
/// Fehlt in den ersten Sekunden nach der Installation das Internet, gilt das nicht als «erledigt»:
/// Ein Netzfehler sperrt weder die Stunde bis zum nächsten Abruf der Liste noch einzelne Bilder für
/// einen Tag. Sobald wieder Netz da ist (`NetworkStatus`) oder die App in den Vordergrund kommt,
/// läuft der Abgleich erneut. Die übrigen Grenzen (6 h, 24 h) bleiben.
enum CoinLogoRetry {
    /// Ab so vielen Bildern gilt «alle fehlgeschlagen» als Netzfehler (nicht als kaputte Bilder).
    static let allFailedMin = 3

    /// Fehlgeschlagener Abruf der Liste: als Versuch speichern (eine Stunde Pause)? Nur bei einem
    /// Server-Fehler, nie bei einem Netzfehler.
    static func recordsAttempt(networkFailure: Bool) -> Bool { !networkFailure }

    /// Fehlgeschlagene Bild-Abrufe (`failed` von `attempted`) am fehlenden Netz? Dann nicht einen Tag
    /// sperren. Ohne Netz immer; mit Netz nur, wenn wirklich alle (mindestens `allFailedMin`) scheiterten.
    static func downloadsFailedForNetwork(online: Bool, failed: Int, attempted: Int) -> Bool {
        failed > 0 && (!online || (failed == attempted && attempted >= allFailedMin))
    }

    /// Abgleich jetzt nachholen? Nur, wenn der letzte am Netz scheiterte und jetzt Netz da ist.
    static func syncNeeded(pendingAfterNetworkFailure: Bool, online: Bool) -> Bool {
        pendingAfterNetworkFailure && online
    }

    /// Wartezeit nach dem ersten am Netz gescheiterten Abgleich (ms).
    static let firstRetryMillis: Int64 = 5_000
    /// Ab dem zweiten Fehlschlag: 15 s, verdoppelt je weiterem Fehlschlag …
    static let retryBaseMillis: Int64 = 15_000
    /// … höchstens 15 Minuten — so entsteht nie eine Flut von Abrufen.
    static let retryMaxMillis: Int64 = 15 * 60_000

    /// Mindestabstand nach `failures` aufeinanderfolgenden, am Netz gescheiterten Abgleichen:
    /// 0 → sofort, 1 → 5 s, 2 → 15 s, 3 → 30 s, 4 → 60 s … höchstens `retryMaxMillis` (wie Android).
    static func backoffMillis(failures: Int) -> Int64 {
        if failures <= 0 { return 0 }
        if failures == 1 { return firstRetryMillis }
        return min(retryBaseMillis << Int64(min(failures - 2, 10)), retryMaxMillis)
    }

    /// Wie lange vor dem nächsten Nachholen noch warten (ms)? 0 = jetzt. Kam das Netz erst lange
    /// nach dem Fehlschlag zurück, sofort; geht die Uhr zurück, die volle Wartezeit.
    static func retryDelayMillis(failures: Int, lastFailureAt: Int64, now: Int64) -> Int64 {
        let backoff = backoffMillis(failures: failures)
        if backoff == 0 { return 0 }
        if now < lastFailureAt { return backoff }
        return min(max(lastFailureAt + backoff - now, 0), backoff)
    }

    // MARK: Bild-Server bremst (Build 46)

    /// Antwort eines Bild-Servers, die eine Pause verlangt statt «Bild kaputt»: 429, 403, 503 — wie Android.
    static func pausesImages(httpCode: Int) -> Bool { httpCode == 429 || httpCode == 403 || httpCode == 503 }
    /// Pause ohne Angabe des Servers.
    static let defaultImagePauseMillis: Int64 = 15 * 60_000
    /// Höchstens so lange (auch bei längerer «Retry-After»-Angabe).
    static let imagePauseMaxMillis: Int64 = 6 * 60 * 60_000

    /// Pause für einen Bild-Server («Retry-After» in Sekunden), 1 Min. … 6 h.
    static func imagePauseMillis(retryAfterSeconds: Int64?) -> Int64 {
        let wait = retryAfterSeconds.map { min(max($0, 0), imagePauseMaxMillis / 1000) * 1000 } ?? defaultImagePauseMillis
        return min(max(wait, 60_000), imagePauseMaxMillis)
    }

    /// Host einer HTTPS-Adresse, klein; nil, wenn keine.
    static func hostOf(_ url: String) -> String? {
        guard url.hasPrefix("https://") else { return nil }
        let host = String(url.dropFirst("https://".count).prefix { $0 != "/" && $0 != "?" && $0 != ":" }).lowercased()
        return host.isEmpty ? nil : host
    }

    /// Pausen je Host speichern: «host=bis;host=bis».
    static func encodePauses(_ pauses: [String: Int64]) -> String {
        pauses.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ";")
    }

    /// Gespeicherte Pausen, nur noch laufende (`now` < bis, höchstens `imagePauseMaxMillis` voraus).
    static func decodePauses(_ text: String?, now: Int64) -> [String: Int64] {
        guard let text else { return [:] }
        var out: [String: Int64] = [:]
        for part in text.split(separator: ";") {
            guard let eq = part.firstIndex(of: "=") else { continue }
            let host = part[..<eq].trimmingCharacters(in: .whitespaces)
            guard let until = Int64(part[part.index(after: eq)...].trimmingCharacters(in: .whitespaces)) else { continue }
            if host.isEmpty || until <= now || until - now > imagePauseMaxMillis { continue }
            out[host] = until
        }
        return out
    }
}
