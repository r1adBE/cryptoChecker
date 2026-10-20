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
    /// Eröffnungsposten («Einstand für Adressguthaben», `AddressCostLots`): Einstand für einen Teil des Guthabens. Ältere
    /// Dateien ohne: leer; ältere Versionen übergehen das Feld.
    var costLots: [AddressCostLot] = []

    init(id: Int64 = 0, chain: AddressChain, address: String, label: String? = nil, lastBalance: String? = nil,
         lastUpdated: Int64 = 0, lastAttempt: Int64 = 0, lastError: String? = nil, lastProvider: String? = nil,
         addedAt: Int64 = 0, observations: [AddressObservation] = [], tokenBalances: [String: String] = [:],
         tokenObservations: [String: [AddressObservation]] = [:], countTokens: Bool = true, xpub: XpubCache? = nil,
         networks: [String]? = nil, countInPortfolio: Bool = true, unknownTokens: Int = 0,
         overlapChecked: Bool = false, overlapLegacy: Bool = false, costLots: [AddressCostLot] = []) {
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
        self.costLots = costLots
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
        case overlapV2, costLots
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
        // Eröffnungsposten (Build 84); unlesbare machen die Datei nicht unlesbar
        costLots = AddressCostLots.clean((try? c.decodeIfPresent([AddressCostLot].self, forKey: .costLots)) ?? [])
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
        if !costLots.isEmpty { try c.encode(costLots, forKey: .costLots) }
    }
}

/// Eintrag in der App-Sicherung: was der Nutzer eingegeben hat, dazu die beobachteten Guthaben (sie bestimmen den
/// Verlauf ab der ersten Beobachtung); das aktuelle Guthaben wird neu geladen. Ältere Sicherungen ohne: leer.
/// JSON: `{"chain":"BTC","address":"…","label":"…","addedAt":…,"observations":[{"t":…,"b":"…"}]}` — gleich wie Android;
/// dazu `"countTokens":false` / `"countInPortfolio":false` (nur wenn aus), `"tokenObservations":{"USDT":[…]}` (nur wenn
/// vorhanden), `"networks":[…]` (nur ETH nach der ersten Suche), `"costLots":[…]` (nur mit Eröffnungsposten),
/// `"overlapChecked":true` (nur entschieden) samt `"overlapLegacy":true` (Entscheid nach der alten Regel).
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
    /// Eröffnungsposten (`WatchedAddress.costLots`); in der Sicherung nur, wenn vorhanden («costLots»).
    var costLots: [AddressCostLot] = []
    /// Überschneidung entschieden (`WatchedAddress.overlapChecked`, z. B. «trotzdem zählen»); in der Sicherung nur, wenn
    /// entschieden — sonst fragte bzw. schaltete die App nach dem Einspielen erneut. Wie Android.
    var overlapChecked = false
    /// Entscheid nach der alten Regel (`WatchedAddress.overlapLegacy`); nur mit `overlapChecked`.
    var overlapLegacy = false

    init(chain: AddressChain, address: String, label: String? = nil, addedAt: Int64 = 0,
         observations: [AddressObservation] = [], countTokens: Bool = true,
         tokenObservations: [String: [AddressObservation]] = [:], networks: [String]? = nil, countInPortfolio: Bool = true,
         costLots: [AddressCostLot] = [], overlapChecked: Bool = false, overlapLegacy: Bool = false) {
        self.chain = chain
        self.address = address
        self.label = label
        self.addedAt = addedAt
        self.observations = observations
        self.countTokens = countTokens
        self.tokenObservations = tokenObservations
        self.networks = networks
        self.countInPortfolio = countInPortfolio
        self.costLots = costLots
        self.overlapChecked = overlapChecked
        self.overlapLegacy = overlapChecked && overlapLegacy
    }

    private enum CodingKeys: String, CodingKey {
        case chain, address, label, addedAt, observations, countTokens, tokenObservations, networks, countInPortfolio
        case costLots, overlapChecked, overlapLegacy
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
        costLots = AddressCostLots.clean((try? c.decodeIfPresent([AddressCostLot].self, forKey: .costLots)) ?? [])
        overlapChecked = (try? c.decodeIfPresent(Bool.self, forKey: .overlapChecked)) ?? false
        overlapLegacy = overlapChecked && ((try? c.decodeIfPresent(Bool.self, forKey: .overlapLegacy)) ?? false)
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
        if !costLots.isEmpty { try c.encode(costLots, forKey: .costLots) }
        if overlapChecked { try c.encode(true, forKey: .overlapChecked) }
        if overlapChecked && overlapLegacy { try c.encode(true, forKey: .overlapLegacy) }
    }
}
