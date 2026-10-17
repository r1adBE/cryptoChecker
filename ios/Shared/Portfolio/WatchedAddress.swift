import Foundation

/// Netz einer beobachteten Adresse; `coin` = Coin im Portfolio, `decimals` = kleinste Einheit. ETH steht für die 0x-Adresse
/// (Ethereum und die gewählten weiteren EVM-Netze, `ChainAssets.evm`). Reihenfolge = Reihenfolge im Bestand.
/// Wie `AddressChain` in `WatchedAddress.kt`.
enum AddressChain: String, Codable, CaseIterable, Sendable {
    case BTC
    case ETH
    case SOL
    case TRX
    case XRP
    case LTC
    case TON
    case ADA

    var coin: String { rawValue }

    var decimals: Int {
        switch self {
        case .BTC, .LTC: return 8
        case .ETH: return 18
        case .SOL, .TON: return 9
        case .TRX, .XRP, .ADA: return 6
        }
    }
}

/// Ein weiteres Guthaben einer Adresse: `label` («USDC.e»), `coin` für Kurs/Bestand, `network` = EVM-Netz oder nil.
struct TokenHolding: Equatable, Sendable {
    let key: String
    let label: String
    let coin: String
    let amount: String
    let network: String?
}

/// Öffentliche Adresse, deren Guthaben als Bestand im Portfolio erscheint (nur lesen: kein Seed,
/// kein Schlüssel). `address` ist normalisiert (`AddressValidation.check`): Bech32 klein,
/// Ethereum mit EIP-55-Prüfsumme — oder ein xpub/ypub/zpub (`isXpub`, Guthaben = Summe der abgeleiteten Adressen).
/// `lastBalance` exakt als Text («0.00012345», `AddressBalance`), nil = noch nie geladen. Zeiten in ms; 0 = nie.
/// Wie `WatchedAddress.kt`.
struct WatchedAddress: Codable, Equatable, Identifiable, Sendable {
    var id: Int64 = 0
    var chain: AddressChain
    var address: String
    var label: String?
    var lastBalance: String?
    /// Letzter erfolgreicher Abruf.
    var lastUpdated: Int64 = 0
    /// Letzter Versuch (Erfolg oder Fehler) — bestimmt mit `AddressBalance.isDue` den nächsten.
    var lastAttempt: Int64 = 0
    /// Technischer Fehlertext des letzten Versuchs; nil nach Erfolg.
    var lastError: String?
    /// Anbieter des letzten Guthabens («mempool.space», «publicnode.com» …) für die Herkunftsangabe.
    var lastProvider: String?
    var addedAt: Int64 = 0
    /// Beobachtete Guthaben (`AddressBalanceHistory.record` je erfolgreichem Abruf): ab der ersten zählt die Adresse in
    /// Heute / 7 T / 1 J und im Wertverlauf (nie rückwirkend). Gehen mit der Adresse weg. Wie Android.
    var observations: [AddressObservation] = []
    /// Weitere Guthaben (`ChainAssets.resolve`): ETH = ERC-20 auf Ethereum (Schlüssel Symbol) und Guthaben auf weiteren
    /// EVM-Netzen («NETZ:SYMBOL»), TRON = TRC-20, Solana = SPL; exakter Text wie zuletzt gelesen (auch «0»).
    var tokenBalances: [String: String] = [:]
    /// Beobachtete Guthaben je Schlüssel von `tokenBalances` (Regeln wie `observations`).
    var tokenObservations: [String: [AddressObservation]] = [:]
    /// «Tokens mitzählen» (ETH, TRON, Solana), Standard an; aus = keine Token-Abfrage, kein Token-Bestand.
    var countTokens = true
    /// Nur xpub: abgeleitete und benutzte Adressen (`XpubScan`).
    var xpub: XpubCache?
    /// Nur ETH: gewählte weitere EVM-Netze (Ethereum zählt immer); nil = noch nie gesucht (erste Abfrage fragt alle).
    var networks: [String]?
    /// false = «Nur anzeigen, nicht zum Portfolio zählen».
    var countInPortfolio = true
    /// Solana: Token mit Guthaben, deren Mint nicht in der Liste steht (nicht gezählt, nur angezeigt).
    var unknownTokens = 0
    /// Überschneidung mit eigenen Transaktionen schon entschieden (beim Hinzufügen gefragt, von Hand umgeschaltet oder
    /// einmal automatisch auf «Nur anzeigen», `WatchedAddressRules.overlapAutoShowOnly`) — danach nie mehr automatisch.
    var overlapChecked = false
    /// Entscheid `overlapChecked` stammt aus der Zeit, als «Nur anzeigen» schon bei gleichem Coin automatisch kam (ohne
    /// Mengenvergleich; gespeichert ohne `overlapV2`). Beim Laden einmal geprüft (`WatchedAddressRules.overlapLegacyRevert`).
    var overlapLegacy = false

    init(id: Int64 = 0, chain: AddressChain, address: String, label: String? = nil, lastBalance: String? = nil,
         lastUpdated: Int64 = 0, lastAttempt: Int64 = 0, lastError: String? = nil, lastProvider: String? = nil,
         addedAt: Int64 = 0, observations: [AddressObservation] = [], tokenBalances: [String: String] = [:],
         tokenObservations: [String: [AddressObservation]] = [:], countTokens: Bool = true, xpub: XpubCache? = nil,
         networks: [String]? = nil, countInPortfolio: Bool = true, unknownTokens: Int = 0,
         overlapChecked: Bool = false, overlapLegacy: Bool = false) {
        self.id = id
        self.chain = chain
        self.address = address
        self.label = label
        self.lastBalance = lastBalance
        self.lastUpdated = lastUpdated
        self.lastAttempt = lastAttempt
        self.lastError = lastError
        self.lastProvider = lastProvider
        self.addedAt = addedAt
        self.observations = observations
        self.tokenBalances = tokenBalances
        self.tokenObservations = tokenObservations
        self.countTokens = countTokens
        self.xpub = xpub
        self.networks = networks
        self.countInPortfolio = countInPortfolio
        self.unknownTokens = unknownTokens
        self.overlapChecked = overlapChecked
        self.overlapLegacy = overlapLegacy
    }

    /// Erweiterter öffentlicher Schlüssel statt einzelner Adresse.
    var isXpub: Bool { chain == .BTC && AddressValidation.isExtendedKey(address) }

    /// Ist dieses EVM-Netz gewählt? (Vor der ersten Suche: alle.)
    func networkSelected(_ id: String) -> Bool { networks?.contains(id) ?? true }

    /// Weitere Guthaben > 0, die zählen: Token nur mit «Tokens mitzählen», Netz-Guthaben weiterer EVM-Netze immer (wenn das
    /// Netz gewählt ist); Reihenfolge Ethereum-Token, dann Netze in der Reihenfolge von `ChainAssets.evm`. Wie Android.
    func tokenHoldings() -> [TokenHolding] {
        guard ChainAssets.hasTokens(chain) else { return [] }
        var items: [(asset: ChainAssets.Asset, amount: String)] = []
        for (key, text) in tokenBalances {
            guard let asset = ChainAssets.resolve(chain, key) else { continue }
            if !asset.native && !countTokens { continue }
            if let net = asset.network, !networkSelected(net) { continue }
            guard let value = PortfolioDecimal.parse(text), value > 0 else { continue }
            items.append((asset, text))
        }
        return items.sorted { $0.asset.order < $1.asset.order }
            .map { TokenHolding(key: $0.asset.key, label: $0.asset.label, coin: $0.asset.coin, amount: $0.amount,
                                network: $0.asset.network) }
    }

    private enum CodingKeys: String, CodingKey {
        case id, chain, address, label, lastBalance, lastUpdated, lastAttempt, lastError, lastProvider, addedAt, observations
        case tokenBalances, tokenObservations, countTokens, xpub, networks, countInPortfolio, unknownTokens, overlapChecked
        case overlapV2
    }

    /// Fehlende Felder (ältere/neuere Dateien) bekommen Standardwerte; Netz und Adresse sind Pflicht.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int64.self, forKey: .id) ?? 0
        chain = try c.decode(AddressChain.self, forKey: .chain)
        address = try c.decode(String.self, forKey: .address)
        label = try c.decodeIfPresent(String.self, forKey: .label)
        lastBalance = try c.decodeIfPresent(String.self, forKey: .lastBalance)
        lastUpdated = try c.decodeIfPresent(Int64.self, forKey: .lastUpdated) ?? 0
        lastAttempt = try c.decodeIfPresent(Int64.self, forKey: .lastAttempt) ?? 0
        lastError = try c.decodeIfPresent(String.self, forKey: .lastError)
        lastProvider = try c.decodeIfPresent(String.self, forKey: .lastProvider)
        addedAt = try c.decodeIfPresent(Int64.self, forKey: .addedAt) ?? 0
        // Unlesbare Beobachtungen machen die Datei nicht unlesbar: dann ohne
        observations = AddressBalanceHistory.clean(
            (try? c.decodeIfPresent([AddressObservation].self, forKey: .observations)) ?? [])
        // Zusatzdaten (Token, Netze, xpub); ältere Dateien ohne: Standard
        let chain = self.chain
        let balances = (try? c.decodeIfPresent([String: String].self, forKey: .tokenBalances)) ?? [:]
        tokenBalances = balances.reduce(into: [:]) { out, item in
            if ChainAssets.resolve(chain, item.key) != nil, let v = PortfolioDecimal.parse(item.value), v >= 0 {
                out[item.key] = PortfolioDecimal.text(v)
            }
        }
        tokenObservations = WatchedAddressRules.cleanTokens(chain,
            (try? c.decodeIfPresent([String: [AddressObservation]].self, forKey: .tokenObservations)) ?? [:])
        countTokens = (try? c.decodeIfPresent(Bool.self, forKey: .countTokens)) ?? true
        xpub = try? c.decodeIfPresent(XpubCache.self, forKey: .xpub)
        networks = chain == .ETH
            ? WatchedAddressRules.cleanNetworks((try? c.decodeIfPresent([String].self, forKey: .networks)) ?? nil) : nil
        countInPortfolio = (try? c.decodeIfPresent(Bool.self, forKey: .countInPortfolio)) ?? true
        unknownTokens = max(0, (try? c.decodeIfPresent(Int.self, forKey: .unknownTokens)) ?? 0)
        overlapChecked = (try? c.decodeIfPresent(Bool.self, forKey: .overlapChecked)) ?? false
        // Ohne `overlapV2`: Entscheid nach der alten Regel (nur gleicher Coin)
        let v2: Bool = (try? c.decodeIfPresent(Bool.self, forKey: .overlapV2)) ?? false
        overlapLegacy = overlapChecked && !v2
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(chain, forKey: .chain)
        try c.encode(address, forKey: .address)
        try c.encodeIfPresent(label, forKey: .label)
        try c.encodeIfPresent(lastBalance, forKey: .lastBalance)
        try c.encode(lastUpdated, forKey: .lastUpdated)
        try c.encode(lastAttempt, forKey: .lastAttempt)
        try c.encodeIfPresent(lastError, forKey: .lastError)
        try c.encodeIfPresent(lastProvider, forKey: .lastProvider)
        try c.encode(addedAt, forKey: .addedAt)
        try c.encode(observations, forKey: .observations)
        if !tokenBalances.isEmpty { try c.encode(tokenBalances, forKey: .tokenBalances) }
        if !tokenObservations.isEmpty { try c.encode(tokenObservations, forKey: .tokenObservations) }
        if !countTokens { try c.encode(false, forKey: .countTokens) }
        try c.encodeIfPresent(xpub, forKey: .xpub)
        try c.encodeIfPresent(networks, forKey: .networks)
        if !countInPortfolio { try c.encode(false, forKey: .countInPortfolio) }
        if unknownTokens > 0 { try c.encode(unknownTokens, forKey: .unknownTokens) }
        if overlapChecked { try c.encode(true, forKey: .overlapChecked) }
        if overlapChecked && !overlapLegacy { try c.encode(true, forKey: .overlapV2) }
    }
}

/// Eintrag in der App-Sicherung: was der Nutzer eingegeben hat, dazu die beobachteten Guthaben (sie bestimmen den
/// Verlauf ab der ersten Beobachtung); das aktuelle Guthaben wird neu geladen. Ältere Sicherungen ohne: leer.
/// JSON: `{"chain":"BTC","address":"…","label":"…","addedAt":…,"observations":[{"t":…,"b":"…"}]}` — gleich wie Android;
/// dazu `"countTokens":false` / `"countInPortfolio":false` (nur wenn aus), `"tokenObservations":{"USDT":[…]}` (nur wenn
/// vorhanden), `"networks":[…]` (nur ETH nach der ersten Suche).
struct WatchedAddressBackupEntry: Codable, Equatable, Sendable {
    var chain: AddressChain
    var address: String
    var label: String?
    var addedAt: Int64 = 0
    var observations: [AddressObservation] = []
    /// «Tokens mitzählen».
    var countTokens = true
    /// Beobachtete Guthaben je Schlüssel (`WatchedAddress.tokenObservations`).
    var tokenObservations: [String: [AddressObservation]] = [:]
    /// Gewählte EVM-Netze (nur ETH); nil = noch nie gesucht.
    var networks: [String]?
    /// «Nur anzeigen» = false.
    var countInPortfolio = true

    init(chain: AddressChain, address: String, label: String? = nil, addedAt: Int64 = 0,
         observations: [AddressObservation] = [], countTokens: Bool = true,
         tokenObservations: [String: [AddressObservation]] = [:], networks: [String]? = nil, countInPortfolio: Bool = true) {
        self.chain = chain
        self.address = address
        self.label = label
        self.addedAt = addedAt
        self.observations = observations
        self.countTokens = countTokens
        self.tokenObservations = tokenObservations
        self.networks = networks
        self.countInPortfolio = countInPortfolio
    }

    private enum CodingKeys: String, CodingKey {
        case chain, address, label, addedAt, observations, countTokens, tokenObservations, networks, countInPortfolio
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chain = try c.decode(AddressChain.self, forKey: .chain)
        address = try c.decode(String.self, forKey: .address)
        label = try c.decodeIfPresent(String.self, forKey: .label)
        addedAt = try c.decodeIfPresent(Int64.self, forKey: .addedAt) ?? 0
        observations = AddressBalanceHistory.clean(
            (try? c.decodeIfPresent([AddressObservation].self, forKey: .observations)) ?? [])
        countTokens = (try? c.decodeIfPresent(Bool.self, forKey: .countTokens)) ?? true
        tokenObservations = WatchedAddressRules.cleanTokens(chain,
            (try? c.decodeIfPresent([String: [AddressObservation]].self, forKey: .tokenObservations)) ?? [:])
        networks = WatchedAddressRules.cleanNetworks((try? c.decodeIfPresent([String].self, forKey: .networks)) ?? nil)
        countInPortfolio = (try? c.decodeIfPresent(Bool.self, forKey: .countInPortfolio)) ?? true
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(chain, forKey: .chain)
        try c.encode(address, forKey: .address)
        try c.encodeIfPresent(label, forKey: .label)
        try c.encode(addedAt, forKey: .addedAt)
        if !observations.isEmpty { try c.encode(observations, forKey: .observations) }
        if !countTokens { try c.encode(false, forKey: .countTokens) }
        if !tokenObservations.isEmpty { try c.encode(tokenObservations, forKey: .tokenObservations) }
        try c.encodeIfPresent(networks, forKey: .networks)
        if !countInPortfolio { try c.encode(false, forKey: .countInPortfolio) }
    }
}

/// Regeln der beobachteten Adressen (Anzahl, Bezeichnung, Anzeige, Sicherung) — wie `WatchedAddressRules` (Kotlin).
enum WatchedAddressRules {

    /// Höchstens so viele Adressen (jede kostet eine Anfrage je Aktualisierung).
    static let maxAddresses = 20

    /// Längste Bezeichnung in Unicode-Skalaren (wie Android: Codepoints).
    static let maxLabel = 40

    /// Schlüssel der Liste in der App-Sicherung (JSON).
    static let backupKey = "watchedAddresses"

    /// Bezeichnung zuschneiden; leer = nil.
    static func cleanLabel(_ raw: String?) -> String? {
        let t = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return nil }
        guard t.unicodeScalars.count > maxLabel else { return t }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: t.unicodeScalars.prefix(maxLabel))
        let cut = String(view).replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
        return cut.isEmpty ? nil : cut
    }

    /// «bc1qxy…0wlh» — Adressen sind ASCII.
    static func shortAddress(_ address: String) -> String {
        address.count <= 14 ? address : String(address.prefix(6)) + "…" + String(address.suffix(4))
    }

    static func sameAddress(_ chain: AddressChain, _ a: String, _ b: String) -> Bool {
        chain == .ETH ? a.lowercased() == b.lowercased() : a == b
    }

    /// Gleiche Adresse schon vorhanden? (Vergleich der normalisierten Form, Ethereum ohne Gross/Klein.)
    static func contains(_ existing: [WatchedAddress], chain: AddressChain, address: String) -> Bool {
        existing.contains { $0.chain == chain && sameAddress(chain, $0.address, address) }
    }

    static func canAdd(_ existing: [WatchedAddress]) -> Bool { existing.count < maxAddresses }

    /// Wiederherstellen: neue Adressen aus der Sicherung (geprüft und normalisiert), ohne Doppelte,
    /// höchstens bis `maxAddresses`. Vorhandene bleiben unverändert. Ungültige Einträge fallen weg.
    static func merge(_ existing: [WatchedAddress], _ incoming: [WatchedAddressBackupEntry]) -> [WatchedAddress] {
        var result: [WatchedAddress] = []
        for entry in incoming {
            if existing.count + result.count >= maxAddresses { break }
            let check = AddressValidation.check(entry.address, expected: entry.chain)
            guard let normalized = check.normalized else { continue }
            if contains(existing, chain: entry.chain, address: normalized)
                || contains(result, chain: entry.chain, address: normalized) { continue }
            var a = WatchedAddress(chain: entry.chain, address: normalized,
                                   label: cleanLabel(entry.label), addedAt: max(0, entry.addedAt),
                                   observations: AddressBalanceHistory.clean(entry.observations),
                                   tokenObservations: cleanTokens(entry.chain, entry.tokenObservations),
                                   countTokens: entry.countTokens,
                                   networks: entry.chain == .ETH ? cleanNetworks(entry.networks) : nil,
                                   countInPortfolio: entry.countInPortfolio)
            // Reihen nur, wo sie heute zählen würden (Token aus, Netz abgewählt: weg)
            let snapshot = a
            a.tokenObservations = a.tokenObservations.filter { counts(snapshot, $0.key) }
            result.append(a)
        }
        return result
    }

    /// Zählt dieser Schlüssel bei dieser Adresse (bekannt, Token nur mit «Tokens mitzählen», Netz gewählt)?
    static func counts(_ a: WatchedAddress, _ key: String) -> Bool {
        guard let asset = ChainAssets.resolve(a.chain, key) else { return false }
        if !asset.native && !a.countTokens { return false }
        guard let net = asset.network else { return true }
        return a.networkSelected(net)
    }

    /// Nur bekannte Schlüssel (`ChainAssets.resolve`) mit gültigen Beobachtungen.
    static func cleanTokens(_ chain: AddressChain, _ map: [String: [AddressObservation]]) -> [String: [AddressObservation]] {
        map.reduce(into: [:]) { out, item in
            guard ChainAssets.resolve(chain, item.key) != nil else { return }
            let list = AddressBalanceHistory.clean(item.value)
            if !list.isEmpty { out[item.key] = list }
        }
    }

    /// Nur bekannte EVM-Netze, Reihenfolge von `ChainAssets.evm`; nil bleibt nil.
    static func cleanNetworks(_ list: [String]?) -> [String]? {
        guard let list else { return nil }
        return ChainAssets.evm.map(\.id).filter { list.contains($0) }
    }

    static func backupEntries(_ list: [WatchedAddress]) -> [WatchedAddressBackupEntry] {
        list.map {
            WatchedAddressBackupEntry(chain: $0.chain, address: $0.address, label: $0.label, addedAt: $0.addedAt,
                                      observations: $0.observations, countTokens: $0.countTokens,
                                      tokenObservations: $0.tokenObservations, networks: $0.networks,
                                      countInPortfolio: $0.countInPortfolio)
        }
    }

    /// Wie `toJson` (Android) für die App-Sicherung: `[{"chain","address","label"?,"addedAt","observations"?,
    /// "countTokens"?,"tokenObservations"?,"networks"?,"countInPortfolio"?}]`.
    static func json(_ entries: [WatchedAddressBackupEntry]) -> [[String: Any]] {
        entries.map { e in
            var o: [String: Any] = ["chain": e.chain.rawValue, "address": e.address, "addedAt": NSNumber(value: e.addedAt)]
            if let label = e.label { o["label"] = label }
            if !e.observations.isEmpty { o["observations"] = AddressBalanceHistory.json(e.observations) }
            if !e.countTokens { o["countTokens"] = false }
            if !e.tokenObservations.isEmpty {
                o["tokenObservations"] = e.tokenObservations.mapValues { AddressBalanceHistory.json($0) }
            }
            if let networks = e.networks { o["networks"] = networks }
            if !e.countInPortfolio { o["countInPortfolio"] = false }
            return o
        }
    }

    /// Einzelne Bitcoin-Adressen, die schon in einem beobachteten xpub stecken (abgeleitete Adressen im
    /// Zwischenspeicher) — zählen nur einmal (über den xpub).
    static func coveredByXpub(_ list: [WatchedAddress]) -> Set<String> {
        var out = Set<String>()
        for a in list where a.isXpub {
            guard let cache = a.xpub else { continue }
            out.formUnion(cache.receive)
            out.formUnion(cache.change)
        }
        return out
    }

    /// Erfolgreicher Abruf — wie Android (`applyFetched`): Guthaben, Zeit, Anbieter, Fehler gelöscht; Beobachtung nur bei
    /// geändertem Guthaben; `tokens` (nil = nicht gelesen): gelesene Schlüssel ersetzen ihren Wert, Token nur mit «Tokens
    /// mitzählen», Netze nur, wenn gewählt; Guthaben 0 ohne frühere Beobachtung wird nicht beobachtet. `networks`: Wahl
    /// nach der ersten Suche; `unknownTokens`: Solana.
    static func applyFetched(_ a: WatchedAddress, time: Int64, balance: String, provider: String?,
                             tokens: [String: String]? = nil, xpub: XpubCache? = nil,
                             networks: [String]? = nil, unknownTokens: Int? = nil) -> WatchedAddress {
        var out = a
        out.lastBalance = balance
        out.lastUpdated = time
        out.lastAttempt = time
        out.lastError = nil
        out.lastProvider = provider
        if let value = PortfolioDecimal.parse(balance) {
            out.observations = AddressBalanceHistory.record(a.observations, time: time, balance: value)
        }
        if let networks, a.chain == .ETH, a.networks == nil { out.networks = cleanNetworks(networks) }
        if let tokens, ChainAssets.hasTokens(a.chain) {
            for key in tokens.keys.sorted() {
                guard counts(out, key), let parsed = PortfolioDecimal.parse(tokens[key]), parsed >= 0 else { continue }
                // Einheitliche Form («3.0» → 3), damit gleiche Guthaben gleich bleiben
                let clean = PortfolioDecimal.text(parsed)
                guard let value = PortfolioDecimal.parse(clean) else { continue }
                out.tokenBalances[key] = clean
                let before = out.tokenObservations[key] ?? []
                if value == 0 && before.isEmpty { continue }
                out.tokenObservations[key] = AddressBalanceHistory.record(before, time: time, balance: value)
            }
        }
        if let xpub { out.xpub = xpub }
        if let unknownTokens, a.chain == .SOL { out.unknownTokens = max(0, unknownTokens) }
        return out
    }

    /// Fehlgeschlagener Abruf: letzter Stand bleibt.
    static func applyError(_ a: WatchedAddress, time: Int64, error: String) -> WatchedAddress {
        var out = a
        out.lastAttempt = time
        out.lastError = error
        return out
    }

    /// «Tokens mitzählen»: aus löscht Token-Guthaben und -Beobachtungen (Netz-Guthaben weiterer EVM-Netze bleiben); an macht
    /// die Adresse sofort fällig. Wie Android.
    static func withCountTokens(_ a: WatchedAddress, _ on: Bool) -> WatchedAddress {
        guard ChainAssets.hasTokens(a.chain), a.countTokens != on else { return a }
        var out = a
        out.countTokens = on
        if on {
            out.lastAttempt = 0
        } else {
            let snapshot = out
            out.tokenBalances = a.tokenBalances.filter { counts(snapshot, $0.key) }
            out.tokenObservations = a.tokenObservations.filter { counts(snapshot, $0.key) }
            out.unknownTokens = 0
        }
        return out
    }

    /// Gewählte EVM-Netze (nur ETH): abgewählte verlieren Guthaben und Reihen; ein neu gewähltes macht die Adresse sofort
    /// fällig. Wie Android.
    static func withNetworks(_ a: WatchedAddress, _ networks: [String]) -> WatchedAddress {
        guard a.chain == .ETH else { return a }
        let clean = cleanNetworks(networks) ?? []
        if clean == a.networks { return a }
        let added = a.networks == nil || clean.contains { !a.networkSelected($0) }
        var next = a
        next.networks = clean
        let snapshot = next
        next.tokenBalances = a.tokenBalances.filter { counts(snapshot, $0.key) }
        next.tokenObservations = a.tokenObservations.filter { counts(snapshot, $0.key) }
        if added { next.lastAttempt = 0 }
        return next
    }

    /// «Nur anzeigen, nicht zum Portfolio zählen» (false) bzw. zählen (true); die Wahl gilt als entschieden. Wie Android.
    static func withCountInPortfolio(_ a: WatchedAddress, _ on: Bool) -> WatchedAddress {
        var out = a
        out.countInPortfolio = on
        out.overlapChecked = true
        out.overlapLegacy = false
        return out
    }

    /// Zählende Adressen, deren Guthaben MENGENMÄSSIG zu eigenen Transaktionen passt (`overlapMatched`) und die noch nie
    /// entschieden wurden (`overlapChecked`): einmal automatisch «Nur anzeigen» (mit Hinweis und «Rückgängig»). Nur
    /// gleicher Coin ohne passende Menge: zählt weiter, der Hinweis «Auch in eigenen Transaktionen» bleibt. Wie Android.
    static func overlapAutoShowOnly(_ addresses: [WatchedAddress], txs: [PortfolioTx]) -> [WatchedAddress] {
        addresses.filter { $0.countInPortfolio && !$0.overlapChecked && !overlapMatched($0, txs: txs).isEmpty }
    }

    /// Einmal automatisch «Nur anzeigen» (`overlapAutoShowOnly`); schon entschieden bzw. nicht zählend: unverändert.
    static func withOverlapShowOnly(_ a: WatchedAddress) -> WatchedAddress {
        guard !a.overlapChecked, a.countInPortfolio else { return a }
        var out = a
        out.countInPortfolio = false
        out.overlapChecked = true
        out.overlapLegacy = false
        return out
    }

    /// Coins der Adresse (`overlap`), deren Menge zu eigenen Transaktionen passt: Guthaben ≈ eigener Bestand des Coins
    /// (`PortfolioCalculator.holdingsAt`, ± 1 %) oder eine eigene Einzahlung/Umbuchung wie ein beobachteter Zugang
    /// (`transferMatches`). Reihenfolge wie `AddressBalance.holdings`. Wie Android.
    static func overlapMatched(_ a: WatchedAddress, txs: [PortfolioTx]) -> [String] {
        let real: [PortfolioTx] = txs.filter { !AddressBalance.isAddressTrade($0.id) }
        var own: [String: Decimal] = [:]
        for h in PortfolioCalculator.holdingsAt(real) { own[h.coin] = h.amount }
        let transferred = Set(transferMatches(a, txs: real).map { PortfolioCalculator.normalizeCoin($0.coin) })
        var counted = a
        counted.countInPortfolio = true
        var out: [String] = []
        for h in AddressBalance.holdings([counted]) {
            guard let balance = PortfolioDecimal.parse(h.amount), balance > 0 else { continue }
            let amountMatch: Bool = own[h.coin].map { nearlyEqual($0, balance, tolerance: overlapTolerance) } ?? false
            if amountMatch || transferred.contains(h.coin) { out.append(h.coin) }
        }
        return out
    }

    /// Alte Entscheide (`overlapLegacy`), die wieder zählen sollen: «Nur anzeigen» ohne passende Menge (`overlapMatched`)
    /// — früher genügte derselbe Coin. Einmal beim Laden, mit Hinweis und «Rückgängig». Wie Android.
    static func overlapLegacyRevert(_ a: WatchedAddress, txs: [PortfolioTx]) -> Bool {
        a.overlapLegacy && !a.countInPortfolio && overlapMatched(a, txs: txs).isEmpty
    }

    /// Alten Entscheid übernehmen (`overlapLegacy` weg): `revert` = zählt wieder und gilt als nicht entschieden (die neue
    /// Regel darf später bei passender Menge umstellen); sonst bleibt die Wahl. Wie Android.
    static func withOverlapMigrated(_ a: WatchedAddress, revert: Bool) -> WatchedAddress {
        guard a.overlapLegacy else { return a }
        var out = a
        out.overlapLegacy = false
        if revert {
            out.countInPortfolio = true
            out.overlapChecked = false
        }
        return out
    }

    /// Coins dieser Adresse (Guthaben > 0, wie sie zählen würde), die auch in eigenen Transaktionen vorkommen. Wie Android.
    static func overlap(_ a: WatchedAddress, ownCoins: Set<String>) -> [String] {
        var counted = a
        counted.countInPortfolio = true
        return AddressBalance.holdings([counted])
            .filter { ownCoins.contains($0.coin) && (PortfolioDecimal.parse($0.amount) ?? 0) > 0 }
            .map(\.coin)
    }

    /// Beim Hinzufügen (Guthaben noch unbekannt): schon eigene Transaktionen im Coin des Netzes?
    static func overlapAtAdd(_ chain: AddressChain, ownCoins: Set<String>) -> Bool { ownCoins.contains(chain.coin) }

    /// Toleranz für `transferMatches`: ± 1 % des Guthabens bzw. der Änderung.
    private static let matchTolerance: Decimal = Decimal(string: "0.01") ?? 0

    /// Toleranz für «Guthaben ≈ eigener Bestand» (`overlapMatched`, einmal automatisch «Nur anzeigen»): ± 5 % — Gebühren,
    /// Staub und kleine nicht gebuchte Zugänge trennen Buchung und Kette oft um mehr als 1 % (sonst zählten beide). Wie Android.
    private static let overlapTolerance: Decimal = Decimal(string: "0.05") ?? 0

    /// Eigene Einzahlungen (`.DEPOSIT`) und Umbuchungen (`.TRANSFER`) im Coin der Adresse, deren Menge (oder erhaltene
    /// Menge nach Coin-Gebühr) dem aktuellen Guthaben oder einem beobachteten Zugang (erste Beobachtung, Zunahme zwischen
    /// zwei Beobachtungen) auf ± 1 % entspricht — dann liegt derselbe Betrag sehr wahrscheinlich doppelt im Portfolio
    /// (Zeilen-Warnung, «Zählen» / «Nur anzeigen»). Zeitlich aufsteigend, je Transaktion einmal (Reihenfolge bei gleicher
    /// Zeit wie die Eingabe). Adress-Einzahlungen (negative Id) zählen nicht. Wie Android.
    static func transferMatches(_ a: WatchedAddress, txs: [PortfolioTx]) -> [PortfolioTx] {
        var counted = a
        counted.countInPortfolio = true
        var targets: [String: [Decimal]] = [:]
        for h in AddressBalance.holdings([counted]) {
            if let value = PortfolioDecimal.parse(h.amount), value > 0 {
                targets[PortfolioCalculator.normalizeCoin(h.coin), default: []].append(value)
            }
        }
        for h in AddressBalanceHistory.of([counted]) {
            var before: Decimal = 0
            for o in h.observations.sorted(by: { $0.time < $1.time }) {
                let delta: Decimal = o.balance - before
                if delta > 0 { targets[PortfolioCalculator.normalizeCoin(h.coin), default: []].append(delta) }
                before = o.balance
            }
        }
        if targets.isEmpty { return [] }
        let hits: [(offset: Int, element: PortfolioTx)] = txs.enumerated().filter { pair -> Bool in
            let t = pair.element
            if AddressBalance.isAddressTrade(t.id) { return false }
            guard t.type == .DEPOSIT || t.type == .TRANSFER,
                  let list = targets[PortfolioCalculator.normalizeCoin(t.coin)] else { return false }
            return list.contains { goal -> Bool in nearlyEqual(t.amountExact, goal) || nearlyEqual(t.receivedExact, goal) }
        }
        // Stabil sortieren (gleiche Zeit: Reihenfolge der Eingabe, wie Kotlin `sortedBy`)
        return hits.sorted { l, r -> Bool in
            l.element.time != r.element.time ? l.element.time < r.element.time : l.offset < r.offset
        }.map(\.element)
    }

    /// Coin-Detail: Treffer (`transferMatches`) aller ZÄHLENDEN Adressen im Coin `coin` — je Transaktion einmal, zeitlich
    /// aufsteigend (gleiche Zeit: Reihenfolge der Adressen). «Nur anzeigen» zählt nicht doppelt, also keine Warnung. Wie Android.
    static func transferMatches(coin: String, addresses: [WatchedAddress], txs: [PortfolioTx]) -> [PortfolioTx] {
        let c = PortfolioCalculator.normalizeCoin(coin)
        var seen = Set<Int64>()
        var hits: [PortfolioTx] = []
        for a in addresses where a.countInPortfolio {
            for t in transferMatches(a, txs: txs) where PortfolioCalculator.normalizeCoin(t.coin) == c {
                if seen.insert(t.id).inserted { hits.append(t) }
            }
        }
        // Stabil sortieren (gleiche Zeit: Reihenfolge wie gefunden, wie Kotlin `sortedBy`)
        return hits.enumerated().sorted { l, r -> Bool in
            l.element.time != r.element.time ? l.element.time < r.element.time : l.offset < r.offset
        }.map(\.element)
    }

    /// |x − goal| ≤ `tolerance` von goal (goal > 0; Standard 1 %).
    private static func nearlyEqual(_ x: Decimal, _ goal: Decimal, tolerance: Decimal = matchTolerance) -> Bool {
        let diff: Decimal = x - goal
        let limit: Decimal = goal * tolerance
        return x > 0 && abs(diff) <= limit
    }

    /// Lesen aus der Sicherung; unlesbare Einträge (unbekanntes Netz, keine Adresse) fallen weg —
    /// geprüft wird in `merge`. Wie `fromJson` (Kotlin).
    static func entries(fromJSON array: [Any]?) -> [WatchedAddressBackupEntry] {
        guard let array else { return [] }
        return array.compactMap { item -> WatchedAddressBackupEntry? in
            guard let o = item as? [String: Any],
                  let chain = AddressChain(rawValue: o["chain"] as? String ?? ""),
                  let address = o["address"] as? String, !address.isEmpty else { return nil }
            let label = o["label"] as? String
            let addedAt = (o["addedAt"] as? NSNumber)?.int64Value ?? 0
            var tokens: [String: [AddressObservation]] = [:]
            for (key, raw) in o["tokenObservations"] as? [String: Any] ?? [:] {
                tokens[key] = AddressBalanceHistory.observations(fromJSON: raw)
            }
            let networks = (o["networks"] as? [Any]).map { $0.compactMap { $0 as? String } }
            return WatchedAddressBackupEntry(chain: chain, address: address, label: label, addedAt: addedAt,
                                             observations: AddressBalanceHistory.observations(fromJSON: o["observations"]),
                                             countTokens: o["countTokens"] as? Bool ?? true,
                                             tokenObservations: cleanTokens(chain, tokens),
                                             networks: cleanNetworks(networks),
                                             countInPortfolio: o["countInPortfolio"] as? Bool ?? true)
        }
    }
}

/// Inhalt von `watched_addresses.json`.
struct WatchedAddressFile: Codable, Equatable {
    var addresses: [WatchedAddress] = []
    var nextId: Int64 = 1

    init(addresses: [WatchedAddress] = [], nextId: Int64 = 1) {
        self.addresses = addresses
        self.nextId = nextId
    }

    private enum CodingKeys: String, CodingKey { case addresses, nextId }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        addresses = try c.decodeIfPresent([WatchedAddress].self, forKey: .addresses) ?? []
        nextId = try c.decodeIfPresent(Int64.self, forKey: .nextId) ?? 1
    }
}

/// Speicher der beobachteten Adressen: eigene JSON-Datei in der App Group (wie Android die Tabelle
/// `watched_addresses` in der Portfolio-Datenbank). Zugriff über `AppData` (`watchedAddresses`).
///
/// Sicherung: Adressen sind Nutzerdaten und gehören in die App-Sicherung (`WatchedAddressRules.backupEntries`
/// unter `backupKey`). Für iCloud-/Geräte-Backups gilt wie für `portfolio.json` der Schalter «Portfolio in
/// Systemsicherung» (`url` steht in `PortfolioStore.backupPolicyURLs`).
enum WatchedAddressStore {
    static var url: URL { SharedStorage.directory.appendingPathComponent("watched_addresses.json") }

    /// Fehlt die Datei: leer. Unlesbar: leer und `intact` false (dann nicht überschreiben).
    static func loadChecked() -> (file: WatchedAddressFile, intact: Bool) {
        guard FileManager.default.fileExists(atPath: url.path) else { return (WatchedAddressFile(), true) }
        guard let data = try? ProtectedFile.read(url),
              let file = try? JSONDecoder().decode(WatchedAddressFile.self, from: data)
        else { return (WatchedAddressFile(), false) }
        return (file, true)
    }

    static func load() -> WatchedAddressFile { loadChecked().file }

    /// Schreibt atomar und setzt danach das Backup-Merkmal neu (`includeInBackup` = «Portfolio in Systemsicherung»).
    @discardableResult
    static func save(_ file: WatchedAddressFile, includeInBackup: Bool) -> Bool {
        guard let data = try? JSONEncoder().encode(file) else { return false }
        do {
            try ProtectedFile.write(data, to: url)
        } catch {
            return false
        }
        BackupExclusion.set(excluded: !includeInBackup, for: url)
        return true
    }

    /// Unlesbare Datei vor dem Ersetzen (Wiederherstellen) unverändert daneben legen
    /// (`watched_addresses.unreadable.json`, wie `PortfolioStore.preserveUnreadable`) — verschoben, nicht
    /// gelesen: geht auch bei kaputtem Inhalt, die Bytes bleiben exakt. Eine ältere daneben gelegte Datei
    /// wird ersetzt. false = keine Datei da oder nicht verschiebbar (dann nicht schreiben).
    static func setAsideUnreadable(at file: URL = url) -> Bool {
        let fm = FileManager.default
        guard fm.fileExists(atPath: file.path) else { return false }
        let aside = SharedStorage.unreadableCopyURL(of: file)
        try? fm.removeItem(at: aside)
        return (try? fm.moveItem(at: file, to: aside)) != nil
    }

    /// Rückrollen eines Ersetzens über eine unlesbare Datei: die daneben gelegte Datei unverändert zurück an
    /// ihren Platz (nie den Ersatzstand zurückschreiben). true = zurückgelegt.
    @discardableResult
    static func putBackUnreadable(at file: URL = url) -> Bool {
        let fm = FileManager.default
        let aside = SharedStorage.unreadableCopyURL(of: file)
        guard fm.fileExists(atPath: aside.path) else { return false }
        try? fm.removeItem(at: file)
        return (try? fm.moveItem(at: aside, to: file)) != nil
    }

    /// Hinzufügen (wie `INSERT … IGNORE`): doppelte Adresse oder volle Liste → nil, sonst neue Id.
    @discardableResult
    static func insert(_ address: WatchedAddress, into file: inout WatchedAddressFile) -> Int64? {
        guard WatchedAddressRules.canAdd(file.addresses),
              !WatchedAddressRules.contains(file.addresses, chain: address.chain, address: address.address) else { return nil }
        var item = address
        let maxId = file.addresses.map(\.id).max() ?? 0
        item.id = max(file.nextId, maxId + 1)
        file.addresses.append(item)
        file.nextId = item.id + 1
        return item.id
    }

    static func remove(id: Int64, from file: inout WatchedAddressFile) {
        file.addresses.removeAll { $0.id == id }
    }

    static func setLabel(id: Int64, label: String?, in file: inout WatchedAddressFile) {
        guard let i = file.addresses.firstIndex(where: { $0.id == id }) else { return }
        file.addresses[i].label = WatchedAddressRules.cleanLabel(label)
    }

    /// Ergebnis eines Abrufs übernehmen: Erfolg setzt Guthaben, Zeit und Anbieter und löscht den Fehler;
    /// ein Fehler lässt das letzte Guthaben stehen. Wie `WatchedAddressDao.saveBalance` / `saveError`.
    static func apply(_ update: WatchedAddressSource.Update, to file: inout WatchedAddressFile) {
        guard let i = file.addresses.firstIndex(where: { $0.id == update.id }) else { return }
        if let fetched = update.fetched {
            // Guthaben, Beobachtungen (nur bei geändertem Guthaben), Token und xpub-Zwischenspeicher — wie Android
            file.addresses[i] = WatchedAddressRules.applyFetched(file.addresses[i], time: update.time,
                                                                 balance: fetched.balance, provider: fetched.provider,
                                                                 tokens: fetched.tokens, xpub: fetched.xpub,
                                                                 networks: fetched.networks, unknownTokens: fetched.unknownTokens)
        } else {
            file.addresses[i] = WatchedAddressRules.applyError(file.addresses[i], time: update.time,
                                                               error: update.error ?? "unknown")
        }
    }

    /// Eine Adresse ändern (Zusatzdaten: «Tokens mitzählen», Netze, «Nur anzeigen»).
    static func change(id: Int64, in file: inout WatchedAddressFile, _ transform: (WatchedAddress) -> WatchedAddress) {
        guard let i = file.addresses.firstIndex(where: { $0.id == id }) else { return }
        file.addresses[i] = transform(file.addresses[i])
    }
}
