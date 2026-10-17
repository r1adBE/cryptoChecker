import Foundation

/// «Gleicher Token?» über mehr als Kürzel + Kursband (K-8) — Spiegel von `TokenIdentity.kt`
/// (gemeinsame Fälle: Parity/token_identity.json).
///
/// Identität eines Paars = voller Name aus der Paarliste der Börse (Gate `base_name`, Upbit/Bithumb
/// `english_name`, Latoken `name`; Binance: `fullName` der Symbolliste, die schon für die Logos geladen
/// wird) und optional Verträge «chain:adresse». Regel:
///  - Verträge auf beiden Seiten: gemeinsamer Vertrag → `.strong`; gleiche Kette, andere Adresse →
///    `.mismatch`; sonst weiter mit den Namen.
///  - Namen auf beiden Seiten: gleich (normalisiert, bekannte Schreibweisen, `known`) → `.medium`;
///    klar verschieden (kein gemeinsames Wort, verschiedene Gruppen in `known`, «Classic» nur auf einer
///    Seite) → `.mismatch`; ähnlich, aber nicht sicher → `.weak`.
///  - Ohne Namen auf einer Seite: `.weak` — Kürzel + Kursband wie bisher.
/// `accept`: `.strong` gilt ohne Kursband, `.medium` und `.weak` nur mit Kursband, `.mismatch` nie — auch
/// wenn die Kurse nah beieinander liegen.
enum TokenIdentity {

    enum Match: String, Sendable {
        case strong = "STRONG"
        case medium = "MEDIUM"
        case weak = "WEAK"
        case mismatch = "MISMATCH"
    }

    /// Identität einer Seite: Name (roh, wie die Börse ihn liefert) und Verträge (`contractKey`).
    struct Identity: Equatable, Sendable {
        var name: String?
        var contracts: Set<String> = []
    }

    /// Bekannte Kürzel mit mehreren Token bzw. mehreren Namen für denselben Token: je Kürzel Gruppen,
    /// eine Gruppe = ein Token. Gleiche Gruppe → `.medium` (auch bei ganz anderem Namen, z. B. nach einer
    /// Umbenennung), verschiedene Gruppen → `.mismatch`. Namen werden wie alle anderen normalisiert.
    static let known: [String: [[String]]] = [
        // Terra 2.0 (neu) gegen Terra Classic (alt; bei manchen Börsen noch unter «LUNA»)
        "LUNA": [
            ["Terra", "Terra 2.0", "Terra Luna 2.0", "LUNA 2.0"],
            ["Terra Classic", "Terra Luna Classic", "LUNA Classic"],
        ],
        "LUNC": [
            ["Terra Classic", "Terra Luna Classic", "LUNA Classic"],
            ["Terra", "Terra 2.0", "Terra Luna 2.0", "LUNA 2.0"],
        ],
        "CAT": [["Simon's Cat", "Simons Cat"], ["Catcoin"]],
        // Aktie (TradFi) gegen tokenisierte Aktie
        "NVDA": [["NVIDIA", "NVIDIA Corporation", "NVIDIA Corp"], ["NVIDIA xStock", "NVIDIA Tokenized Stock"]],
        "TON": [["Toncoin", "The Open Network", "Gram"], ["Tokamak Network"]],
        "GMT": [["STEPN", "Green Metaverse Token"], ["GoMining", "GoMining Token"]],
        "HOT": [["Holo", "Holochain", "HoloToken"], ["Hydro Protocol", "Hydro"]],
        // Umbenennungen: andere Namen, derselbe Token
        "FET": [["Fetch.ai", "Artificial Superintelligence Alliance", "ASI Alliance"]],
        "POL": [["Polygon", "Polygon Ecosystem Token", "Matic Network"]],
        "MATIC": [["Polygon", "Matic Network"]],
        "EGLD": [["MultiversX", "Elrond"]],
        "KAIA": [["Kaia", "Klaytn"]],
        "XDC": [["XDC Network", "XinFin", "XinFin Network"]],
        "XRP": [["Ripple", "XRP Ledger"]],
        "QTUM": [["Qtum", "Quantum"]],
        "S": [["Sonic", "Fantom"]],
    ]

    /// Füllwörter, die zwei Namen desselben Tokens unterscheiden können («Tether USD», «Gate Token»).
    private static let stopWords: Set<String> = ["token", "tokens", "coin", "network", "protocol", "finance", "the", "new", "labs", "foundation"]

    /// Zusätze, die einen anderen Token bezeichnen, wenn nur eine Seite sie trägt.
    private static let markers: [String] = ["classic", "xstock", "tokenized"]

    private static func isAlnum(_ c: Character) -> Bool {
        guard let a = c.asciiValue else { return false }
        return (97...122).contains(a) || (48...57).contains(a)
    }

    /// Kleinbuchstaben ohne Akzente; Klammern («(prev. FTM)») entfallen, Apostrophe verbinden («simons»).
    private static func words(_ raw: String) -> [String] {
        var s = raw.folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX")).lowercased()
        s = s.replacingOccurrences(of: "\\([^)]*\\)|\\[[^\\]]*\\]", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "['’`]", with: "", options: .regularExpression)
        return s.split(whereSeparator: { !isAlnum($0) }).map(String.init).filter { !stopWords.contains($0) }
    }

    private static func compactSymbol(_ symbol: String) -> String {
        String(symbol.lowercased().filter { isAlnum($0) })
    }

    /// Name für den Vergleich (Wörter mit Leerzeichen); nil = kein brauchbarer Name (leer, nur Füllwörter,
    /// nicht lateinisch oder bloss das Kürzel, z. B. «BNB» für BNB).
    static func normalizeName(_ name: String?, symbol: String) -> String? {
        guard let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let w = words(name)
        if w.isEmpty { return nil }
        if w.joined() == compactSymbol(symbol) { return nil }
        return w.joined(separator: " ")
    }

    /// Vertrag «chain:adresse»; EVM-Adressen (0x…) ohne Gross-/Kleinschreibung. nil = unbrauchbar.
    static func contractKey(chain: String, address: String) -> String? {
        let c = chain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var a = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if a.lowercased().hasPrefix("0x") { a = a.lowercased() }
        if c.isEmpty || a.isEmpty { return nil }
        return "\(c):\(a)"
    }

    private static func normalizedContract(_ key: String) -> String? {
        guard let i = key.firstIndex(of: ":"), i > key.startIndex else { return nil }
        return contractKey(chain: String(key[..<i]), address: String(key[key.index(after: i)...]))
    }

    private static func chain(of key: String) -> String {
        key.firstIndex(of: ":").map { String(key[..<$0]) } ?? key
    }

    private static func groupOf(_ symbol: String, _ normalized: String) -> Int? {
        let k = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard let groups = known[k] else { return nil }
        return groups.firstIndex { g in g.contains { normalizeName($0, symbol: symbol) == normalized } }
    }

    /// Ist `short` (mind. 3 Zeichen) eine Teilfolge von `long` («qtum» in «quantum»)?
    private static func isSubsequence(_ short: String, _ long: String) -> Bool {
        let s = Array(short)
        if s.count < 3 { return false }
        var i = 0
        for ch in long where i < s.count && s[i] == ch { i += 1 }
        return i == s.count
    }

    /// Vergleich zweier Namen desselben Kürzels `symbol`.
    static func matchNames(symbol: String, _ a: String?, _ b: String?) -> Match {
        guard let na = normalizeName(a, symbol: symbol), let nb = normalizeName(b, symbol: symbol) else { return .weak }
        if let ga = groupOf(symbol, na), let gb = groupOf(symbol, nb) { return ga == gb ? .medium : .mismatch }
        if na == nb { return .medium }
        let wa = Set(na.split(separator: " ").map(String.init))
        let wb = Set(nb.split(separator: " ").map(String.init))
        if markers.contains(where: { wa.contains($0) != wb.contains($0) }) { return .mismatch }
        let ca = na.replacingOccurrences(of: " ", with: "")
        let cb = nb.replacingOccurrences(of: " ", with: "")
        let (short, long) = ca.count <= cb.count ? (ca, cb) : (cb, ca)
        if short.count >= 4 && long.contains(short) { return .medium }
        if wa.contains(where: { $0.count >= 3 && wb.contains($0) }) || isSubsequence(short, long) { return .weak }
        return .mismatch
    }

    /// Vergleich zweier Identitäten desselben Kürzels `symbol`; nil = keine Angaben (`.weak`).
    static func match(symbol: String, _ a: Identity?, _ b: Identity?) -> Match {
        let ca = Set((a?.contracts ?? []).compactMap { normalizedContract($0) })
        let cb = Set((b?.contracts ?? []).compactMap { normalizedContract($0) })
        if !ca.isEmpty && !cb.isEmpty {
            if !ca.isDisjoint(with: cb) { return .strong }
            let chains = Set(ca.map { chain(of: $0) })
            if cb.contains(where: { chains.contains(chain(of: $0)) }) { return .mismatch }
        }
        return matchNames(symbol: symbol, a?.name, b?.name)
    }

    /// Darf die Reihe/der Kurs der anderen Seite überhaupt geprüft werden (kein klarer Widerspruch)?
    static func compatible(symbol: String, _ a: String?, _ b: String?) -> Bool {
        matchNames(symbol: symbol, a, b) != .mismatch
    }

    /// Börsen-Schlüssel (`NameIndex.markets`) der übrigen Anbieter der Ausweich-Kette — deren Namen aus
    /// der eigenen Paarliste, sobald die Börse Namen liefert (Binance-Anbieter: `NameIndex.binance`).
    static let providerMarkets: [String: String] = [
        DataFreshness.binanceUS: "BinanceUs",
        DataFreshness.coinbase: "Coinbase",
        DataFreshness.bybit: "Bybit",
    ]

    /// Darf eine Kerzenreihe der Ausweich-Kette von `provider` für den Coin `symbol` erscheinen (Mini-Chart,
    /// 24-h-Bezug, Widget-Chart)? Nie bei klar anderem Namen (`compatible`): Widerspricht der eigene Name
    /// `ownName` dem Binance-Namen `binanceName`, steht das Kürzel an den grossen Börsen für einen anderen
    /// Token — dann keine Reihe, von keinem Anbieter (auch Binance.US, Coinbase …). Dazu der Name des Anbieters
    /// selbst `providerName` (falls bekannt). Unbekannte Namen: Kürzel + Kursband wie bisher.
    /// Wie `TokenIdentity.fallbackSeriesAllowed` (Android).
    static func fallbackSeriesAllowed(symbol: String, ownName: String?, provider: String?, binanceName: String?,
                                      providerName: String? = nil) -> Bool {
        guard provider != nil else { return true }
        if !compatible(symbol: symbol, ownName, binanceName) { return false }
        return compatible(symbol: symbol, ownName, providerName)
    }

    /// Darf die Reihe der Ausweich-Kette von `provider` (eine ANDERE Börse) die %-Veränderung des Paars liefern?
    /// Strenger als `fallbackSeriesAllowed`: Kurs des Paars gegen die Eröffnung einer fremden Reihe ist nur
    /// sinnvoll, wenn es belegt derselbe Token ist — Kürzel + Kursband (±25 %) allein liessen einen anderen Token
    /// oder eine fremde Prämie als Veränderung durch (bis gut 20 Punkte daneben). Verlangt also den eigenen Namen
    /// und einen gleichen Namen (`.medium`) beim Anbieter (`providerName`, sonst `binanceName`). Unbekannt →
    /// false: lieber «—» als ein geratener Wert. Wie `TokenIdentity.fallbackChangeAllowed` (Android).
    static func fallbackChangeAllowed(symbol: String, ownName: String?, provider: String?, binanceName: String?,
                                      providerName: String? = nil) -> Bool {
        guard let provider, !provider.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        guard fallbackSeriesAllowed(symbol: symbol, ownName: ownName, provider: provider, binanceName: binanceName,
                                    providerName: providerName) else { return false }
        return matchNames(symbol: symbol, ownName, providerName ?? binanceName) == .medium
    }

    /// Darf die Quelle `host` der Kerzen-Kette (Aktionsblatt-Chart, Volumen-Reihe) für `symbol` einspringen?
    /// Gleiche Regel wie `fallbackSeriesAllowed` mit dem Anbieter des Hosts (`DataFreshness.candleProvider`):
    /// Widerspricht der eigene Name `ownName` dem Binance-Namen, fällt die ganze Kette aus (auch Binance.US,
    /// Coinbase) — Gate «CAT» zeigt nie den Chart eines anderen Tokens. Wie `TokenIdentity.chainSourceAllowed` (Android).
    static func chainSourceAllowed(symbol: String, ownName: String?, host: String, binanceName: String?,
                                   providerName: String? = nil) -> Bool {
        fallbackSeriesAllowed(symbol: symbol, ownName: ownName, provider: DataFreshness.candleProvider(host: host),
                              binanceName: binanceName, providerName: providerName)
    }

    /// Gleicher Token? `.strong` ohne Kursband, `.medium`/`.weak` nur mit `bandOk`, `.mismatch` nie.
    static func accept(_ match: Match, bandOk: Bool) -> Bool {
        switch match {
        case .strong: return true
        case .medium, .weak: return bandOk
        case .mismatch: return false
        }
    }

    /// Widersprechen sich bekannte Namen (`names`, je Paar) desselben Kürzels?
    static func conflicting(symbol: String, _ names: [String?]) -> Bool {
        let knownNames = names.filter { normalizeName($0, symbol: symbol) != nil }
        for i in knownNames.indices {
            for j in knownNames.indices where j > i {
                if matchNames(symbol: symbol, knownNames[i], knownNames[j]) == .mismatch { return true }
            }
        }
        return false
    }

    /// Gemeinsamer Name mehrerer Paare: der erste bekannte, wenn keiner widerspricht; sonst nil.
    static func consensus(symbol: String, _ names: [String?]) -> String? {
        if conflicting(symbol: symbol, names) { return nil }
        return names.first { normalizeName($0, symbol: symbol) != nil } ?? nil
    }

    /// Längster gespeicherter Name (Schutz vor unbrauchbaren Einträgen).
    static let nameMax = 80

    /// Namen je Börse (`markets`: Börsen-Schlüssel → Kürzel → Name, aus der Paarliste) und von Binance
    /// (`binance`: Kürzel → `fullName` der Symbolliste). Kürzel gross geschrieben.
    struct NameIndex: Equatable, Sendable {
        var markets: [String: [String: String]] = [:]
        var binance: [String: String] = [:]

        func pairName(marketKey: String, base: String) -> String? { markets[marketKey]?[TokenIdentity.key(base)] }
        func binanceName(_ base: String) -> String? { binance[TokenIdentity.key(base)] }
    }

    static func key(_ base: String) -> String {
        base.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Nur brauchbare Einträge: Kürzel und Name nicht leer, Name höchstens `nameMax` Zeichen.
    static func cleaned(_ names: [String: String]) -> [String: String] {
        var out: [String: String] = [:]
        for (base, name) in names {
            let k = key(base)
            let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
            if k.isEmpty || n.isEmpty || n.count > nameMax { continue }
            if out[k] == nil { out[k] = n }
        }
        return out
    }

    static func encode(_ index: NameIndex) -> Data? {
        let root: [String: Any] = ["v": 1, "markets": index.markets, "binance": index.binance]
        return try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    /// Gespeicherter Stand; unlesbar oder leer → leerer Index.
    static func decode(_ data: Data?) -> NameIndex {
        guard let data, !data.isEmpty,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return NameIndex() }
        func names(_ raw: Any?) -> [String: String] {
            guard let o = raw as? [String: Any] else { return [:] }
            var m: [String: String] = [:]
            for (k, v) in o { if let s = v as? String, !s.isEmpty { m[k] = s } }
            return cleaned(m)
        }
        var markets: [String: [String: String]] = [:]
        for (k, v) in (root["markets"] as? [String: Any]) ?? [:] {
            let n = names(v)
            if !n.isEmpty { markets[k] = n }
        }
        return NameIndex(markets: markets, binance: names(root["binance"]))
    }
}
