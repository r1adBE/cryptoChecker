import Foundation

// Regeln der beobachteten Adressen — Typen in WatchedAddress.swift, Speicher in WatchedAddressStore.swift.

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

    /// «bc1qxy…0wlh» — Adressen sind ASCII. Taproot-Konto: «tr(xpub6B…UsQ)» (der Schlüssel, nicht der Pfad am Ende).
    static func shortAddress(_ address: String) -> String {
        if let inner = Bip32.taprootAccount(address) { return "tr(" + shortAddress(inner) + ")" }
        return address.count <= 14 ? address : String(address.prefix(6)) + "…" + String(address.suffix(4))
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
    /// höchstens bis `maxAddresses`. Vorhandene bleiben unverändert. Ungültige Einträge fallen weg. `now` = `pendingSince`.
    static func merge(_ existing: [WatchedAddress], _ incoming: [WatchedAddressBackupEntry],
                      now: Int64 = TimeUtils.nowMillis) -> [WatchedAddress] {
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
                                   countInPortfolio: entry.countInPortfolio,
                                   overlapChecked: entry.overlapChecked,
                                   overlapLegacy: entry.overlapChecked && entry.overlapLegacy,
                                   costLots: AddressCostLots.clean(entry.costLots), pendingSince: now)
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
                                      countInPortfolio: $0.countInPortfolio, costLots: $0.costLots,
                                      overlapChecked: $0.overlapChecked, overlapLegacy: $0.overlapChecked && $0.overlapLegacy)
        }
    }

    /// Wie `toJson` (Android) für die App-Sicherung: `[{"chain","address","label"?,"addedAt","observations"?,
    /// "countTokens"?,"tokenObservations"?,"networks"?,"countInPortfolio"?,"costLots"?,"overlapChecked"?,"overlapLegacy"?}]`.
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
            // Eröffnungsposten wie Android «costLots» (gleiche Felder wie die Codable-Form von `AddressCostLot`)
            if !e.costLots.isEmpty, let data = try? JSONEncoder().encode(e.costLots),
               let lots = try? JSONSerialization.jsonObject(with: data) {
                o["costLots"] = lots
            }
            if e.overlapChecked { o["overlapChecked"] = true }
            if e.overlapChecked && e.overlapLegacy { o["overlapLegacy"] = true }
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
    /// nach der ersten Suche; `unknownTokens`: Solana; `keptTokens`: nicht bestätigte Token (`withKeptTokens`).
    static func applyFetched(_ a: WatchedAddress, time: Int64, balance: String, provider: String?,
                             tokens: [String: String]? = nil, xpub: XpubCache? = nil,
                             networks: [String]? = nil, unknownTokens: Int? = nil,
                             keptTokens: [String]? = nil) -> WatchedAddress {
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
        return withKeptTokens(out, keptTokens)
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
            out.staleTokens = a.staleTokens.filter { counts(snapshot, $0) }
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
    /// entschieden wurden (`overlapChecked`): einmal automatisch «Nur anzeigen» (mit Hinweis und «Rückgängig»). Nur wenn
    /// ALLE Coins mit Guthaben > 0 passen (sonst fielen andere Token still aus dem Portfolio); teilweise bzw. gleicher
    /// Coin ohne passende Menge: zählt weiter, der Hinweis «Auch in eigenen Transaktionen» bleibt. Wie Android.
    static func overlapAutoShowOnly(_ addresses: [WatchedAddress], txs: [PortfolioTx]) -> [WatchedAddress] {
        addresses.filter { $0.countInPortfolio && !$0.overlapChecked && overlapAllMatched($0, txs: txs) }
    }

    /// Alle Coins der Adresse mit Guthaben > 0 passen mengenmässig (`overlapMatched`); leere Adresse: nein. Wie Android.
    static func overlapAllMatched(_ a: WatchedAddress, txs: [PortfolioTx]) -> Bool {
        var counted = a
        counted.countInPortfolio = true
        let positive = AddressBalance.holdings([counted]).filter { (PortfolioDecimal.parse($0.amount) ?? 0) > 0 }.count
        let matched = overlapMatched(a, txs: txs)
        return !matched.isEmpty && matched.count == positive
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
                                             countInPortfolio: o["countInPortfolio"] as? Bool ?? true,
                                             costLots: AddressCostLots.lots(fromJSON: o["costLots"]),
                                             overlapChecked: o["overlapChecked"] as? Bool ?? false,
                                             overlapLegacy: o["overlapLegacy"] as? Bool ?? false)
        }
    }
}
