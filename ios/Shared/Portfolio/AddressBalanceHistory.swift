import Foundation

/// Eine Beobachtung des Guthabens einer Adresse: Zeitpunkt (ms) und Guthaben (exakt, ≥ 0).
/// Gespeichert wie Android: `{"t":1727136000000,"b":"0.3"}` (Guthaben exakt als Text, nie über Double).
struct AddressObservation: Codable, Equatable, Sendable {
    let time: Int64
    let balance: Decimal

    init(time: Int64, balance: Decimal) {
        self.time = time
        self.balance = balance
    }

    private enum CodingKeys: String, CodingKey { case t, b }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        time = try c.decode(Int64.self, forKey: .t)
        guard let value = PortfolioDecimal.parse(try c.decode(String.self, forKey: .b)) else {
            throw DecodingError.dataCorruptedError(forKey: .b, in: c, debugDescription: "Guthaben ungültig")
        }
        balance = value
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(time, forKey: .t)
        try c.encode(PortfolioDecimal.text(balance), forKey: .b)
    }
}

/// Adress-Guthaben zu einem Zeitpunkt (`AddressBalanceHistory.balancesAt`): `balances` je Coin (> 0) und `unobserved` =
/// Coins, deren Reihe erst danach beginnt (dort nicht beobachtet). Wie Android `AddressBalancesAt`.
struct AddressBalancesAt: Equatable, Sendable {
    let balances: [String: Decimal]
    let unobserved: Set<String>
}

/// Beobachtete Guthaben einer Adresse (zeitlich aufsteigend) — Grundlage für Heute / 7 T / 1 J und den Wertverlauf.
/// Regeln exakt wie `AddressBalanceHistory.kt` (dort beschrieben): vor der ersten Beobachtung zählt die Adresse
/// nicht; erste Beobachtung und jede Änderung sind Zu-/Abflüsse zum Marktkurs ihres Zeitpunkts (nie Gewinn, nie
/// rückwirkend); was die App schon bucht (Eröffnungsposten, erfasste Bewegungen der Adress-Wallet), zählt nicht noch
/// einmal (`moves`, `AddressBooked`); `append` behält die erste Beobachtung überhaupt und je UTC-Tag die letzte.
struct AddressBalanceHistory: Codable, Equatable, Sendable {
    let chain: AddressChain
    let address: String
    var observations: [AddressObservation]
    /// Coin eines weiteren Guthabens (Token, Netz-Guthaben eines anderen EVM-Netzes); nil = Guthaben des Netzes.
    var token: String? = nil
    /// Schlüssel des weiteren Guthabens (`WatchedAddress.tokenBalances`); unterscheidet z. B. USDC und USDC.e.
    var key: String? = nil
    /// Gebuchte Bewegungen, die diese Reihe enthält (Eröffnungsposten, erfasste Bewegungen der Adress-Wallet) — nur an der
    /// ersten Reihe je Adresse und Coin, gesetzt von `AddressBooked.histories`; `trades` rechnet sie heraus.
    var moves: [AddressMove] = []

    private enum CodingKeys: String, CodingKey { case chain, address, observations, token, key }

    var coin: String { token ?? chain.coin }

    /// Gleiche Reihe (eine Adresse zählt je Schlüssel nur einmal).
    private var series: String { key ?? token ?? chain.coin }

    /// Reihe nach einer neuen Beobachtung: zeitlich eingeordnet; behalten werden die erste Beobachtung überhaupt
    /// und je UTC-Tag die letzte. Negatives Guthaben ändert nichts.
    static func append(_ observations: [AddressObservation], time: Int64, balance: Decimal) -> [AddressObservation] {
        guard balance >= 0 else { return observations }
        // Stabil sortiert wie Android (`sortedBy`)
        let sorted = (observations + [AddressObservation(time: time, balance: balance)]).enumerated()
            .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
            .map(\.element)
        var out: [AddressObservation] = []
        for o in sorted {
            // Gleicher Tag wie die vorige (nicht die erste überhaupt): die spätere ersetzt sie
            if out.count > 1, let last = out.last,
               PortfolioHistory.epochDay(utcMillis: last.time) == PortfolioHistory.epochDay(utcMillis: o.time) {
                out[out.count - 1] = o
            } else {
                out.append(o)
            }
        }
        return out
    }

    /// Guthaben-Änderungen als Einträge für Zeiträume und Verlauf: je Beobachtung mit geänderter Summe eine Einzahlung
    /// (+) bzw. Auszahlung (−) der Differenz OHNE Preis mit negativer Id (`AddressBalance.isAddressTrade`) —
    /// `PortfolioPeriodChange` und `PortfolioHistory` bewerten sie zum Marktkurs ihres Zeitpunkts. Dieselbe Adresse
    /// (gleiches Netz, gleicher Coin) zählt nur einmal (die erste Reihe). Reihen mit gebuchten Bewegungen (`moves`) zählen
    /// ohne sie (`AddressBooked.held`). Wie Android.
    static func trades(_ histories: [AddressBalanceHistory]) -> [PortfolioTx] {
        var out: [PortfolioTx] = []
        var kept: [AddressBalanceHistory] = []
        for h in histories where !kept.contains(where: {
            $0.chain == h.chain && $0.series == h.series && WatchedAddressRules.sameAddress(h.chain, $0.address, h.address)
        }) {
            kept.append(h)
        }
        var nextId: Int64 = -1_000_001
        for (index, h) in kept.enumerated() {
            // Mit gebuchten Bewegungen: alle Reihen dieser Adresse und dieses Coins zusammen ohne sie (`AddressBooked.held`)
            let group = kept.indices.filter { AddressBooked.sameGroup(kept[$0], h) }
            let steps: [AddressObservation]
            if group.allSatisfy({ kept[$0].moves.isEmpty }) {
                steps = h.observations.enumerated()
                    .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
                    .map(\.element)
            } else if group.first == index {
                steps = AddressBooked.held(group.map { kept[$0] })
            } else {
                continue
            }
            var previous: Decimal = 0
            for o in steps where o.balance >= 0 {
                let delta = o.balance - previous
                previous = o.balance
                if delta == 0 { continue }
                out.append(PortfolioTx(id: nextId, coin: h.coin, type: delta > 0 ? .DEPOSIT : .WITHDRAWAL,
                                       amountExact: delta > 0 ? delta : -delta, priceExact: nil, time: o.time))
                nextId -= 1
            }
        }
        return out
    }

    /// Beobachtung nach einem erfolgreichen Abruf speichern: gleiches Guthaben wie die letzte Beobachtung → Reihe
    /// unverändert (keine Information, kein Schreiben); sonst `append`. Wie Android.
    static func record(_ observations: [AddressObservation], time: Int64, balance: Decimal) -> [AddressObservation] {
        if let last = observations.max(by: { $0.time < $1.time }), last.balance == balance, time >= last.time {
            return observations
        }
        return append(observations, time: time, balance: balance)
    }

    /// Reihen aller beobachteten Adressen (`WatchedAddress.observations`). Ohne gespeicherte Beobachtung, aber mit
    /// geladenem Guthaben: dieses ab seinem Abruf (`lastUpdated`) — nie früher. Dazu je weiterem Guthaben, das zählt
    /// (`WatchedAddressRules.counts`), eine Reihe (`tokenObservations`); Adressen «Nur anzeigen» fehlen ganz. Einzelne Bitcoin-Adressen,
    /// die in einem beobachteten xpub stecken, fehlen (zählen über den xpub). Wie Android.
    static func of(_ addresses: [WatchedAddress]) -> [AddressBalanceHistory] {
        let counted = addresses.filter(\.countInPortfolio)
        let covered = WatchedAddressRules.coveredByXpub(counted)
        var out: [AddressBalanceHistory] = []
        for a in counted {
            if a.chain == .BTC && !a.isXpub && covered.contains(a.address) { continue }
            var obs = a.observations
            if obs.isEmpty, let balance = PortfolioDecimal.parse(a.lastBalance), balance >= 0, a.lastUpdated > 0 {
                obs = [AddressObservation(time: a.lastUpdated, balance: balance)]
            }
            if !obs.isEmpty { out.append(AddressBalanceHistory(chain: a.chain, address: a.address, observations: obs)) }
            var series: [(asset: ChainAssets.Asset, list: [AddressObservation])] = []
            for (key, list) in a.tokenObservations {
                guard let asset = ChainAssets.resolve(a.chain, key), !list.isEmpty, WatchedAddressRules.counts(a, key) else { continue }
                series.append((asset, list))
            }
            for item in series.sorted(by: { $0.asset.order < $1.asset.order }) {
                out.append(AddressBalanceHistory(chain: a.chain, address: a.address, observations: item.list,
                                                 token: item.asset.coin, key: item.asset.key))
            }
        }
        return out
    }

    /// Gültige Beobachtungen (Guthaben ≥ 0, Zeit > 0), zeitlich aufsteigend (stabil wie Android).
    static func clean(_ observations: [AddressObservation]) -> [AddressObservation] {
        observations.enumerated()
            .filter { $0.element.balance >= 0 && $0.element.time > 0 }
            .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
            .map(\.element)
    }

    /// Sicherung: `[{"t":…,"b":"…"}]` wie Android (`toJson`).
    static func json(_ observations: [AddressObservation]) -> [[String: Any]] {
        observations.map { ["t": NSNumber(value: $0.time), "b": PortfolioDecimal.text($0.balance)] }
    }

    /// Lesen aus JSON (`JSONSerialization`); unlesbare Einträge fallen weg (`clean`). Wie Android (`fromJson`).
    static func observations(fromJSON raw: Any?) -> [AddressObservation] {
        guard let array = raw as? [Any] else { return [] }
        let out: [AddressObservation] = array.compactMap { item in
            guard let o = item as? [String: Any], let t = o["t"] as? NSNumber, CFGetTypeID(t) != CFBooleanGetTypeID(),
                  let value = PortfolioDecimal.parse(o["b"] as? String) else { return nil }
            return AddressObservation(time: t.int64Value, balance: value)
        }
        return clean(out)
    }

    /// Text wie die Android-Spalte (`encode`): `[{"t":1,"b":"0.5"}]`; leer = nil.
    static func encode(_ observations: [AddressObservation]) -> String? {
        guard !observations.isEmpty else { return nil }
        return "[" + observations.map { "{\"t\":\($0.time),\"b\":\"\(PortfolioDecimal.text($0.balance))\"}" }
            .joined(separator: ",") + "]"
    }

    /// Text lesen (`decode` wie Android); unlesbar = leer.
    static func decode(_ text: String?) -> [AddressObservation] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = text.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data) else { return [] }
        return observations(fromJSON: raw)
    }

    /// Beobachtetes Guthaben je Coin als Stufen (Summe der Adressen, dieselbe nur einmal), Grossschreibung — für den
    /// 24-h-Verlauf des Widgets (`PortfolioWidgetSeries.hourly`). Wie Android.
    static func stepsByCoin(_ histories: [AddressBalanceHistory]) -> [String: [AddressObservation]] {
        var out: [String: [AddressObservation]] = [:]
        var sums: [String: Decimal] = [:]
        let ordered = trades(histories).enumerated()
            .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
            .map(\.element)
        for t in ordered {
            let sum = (sums[t.coin] ?? 0) + (t.type == .DEPOSIT ? t.amountExact : -t.amountExact)
            sums[t.coin] = sum
            var list = out[t.coin] ?? []
            // Gleiche Zeit (zwei Adressen): eine Stufe
            if let last = list.last, last.time == t.time {
                list[list.count - 1] = AddressObservation(time: t.time, balance: sum)
            } else {
                list.append(AddressObservation(time: t.time, balance: sum))
            }
            out[t.coin] = list
        }
        return out
    }

    /// Guthaben der Stufen zum Zeitpunkt `time`: letzte Stufe ≤ `time`, davor 0.
    static func balanceAt(_ steps: [AddressObservation], time: Int64) -> Decimal {
        steps.last(where: { $0.time <= time })?.balance ?? 0
    }

    /// Guthaben je Coin (Grossschreibung) zum Zeitpunkt `time` (Stichtag: `AddressCutoff` nimmt die nicht beobachteten Coins):
    /// je Reihe die letzte Beobachtung ≤ `time` (Summe der Adressen, dieselbe nur einmal, wie `trades`); nur Guthaben
    /// > 0. `unobserved` = Coins mit einer Reihe, die erst NACH `time` beginnt — an dem Tag nicht beobachtet, ihr
    /// Guthaben fehlt (nie rückwirkend). Wie Android `balancesAt`.
    static func balancesAt(_ histories: [AddressBalanceHistory], time: Int64) -> AddressBalancesAt {
        var sums: [String: Decimal] = [:]
        var unobserved: Set<String> = []
        var seen: [AddressBalanceHistory] = []
        for h in histories {
            if seen.contains(where: {
                $0.chain == h.chain && $0.series == h.series && WatchedAddressRules.sameAddress(h.chain, $0.address, h.address)
            }) {
                continue
            }
            seen.append(h)
            let coin = PortfolioCalculator.normalizeCoin(h.coin)
            let obs = clean(h.observations)
            if obs.isEmpty { continue }
            guard let at = obs.last(where: { $0.time <= time }) else {
                unobserved.insert(coin)
                continue
            }
            sums[coin] = (sums[coin] ?? 0) + at.balance
        }
        return AddressBalancesAt(balances: sums.filter { $0.value > 0 }, unobserved: unobserved)
    }

    /// Zuletzt beobachtetes Guthaben je Coin (Summe der Adressen, dieselbe nur einmal), Grossschreibung.
    static func latestByCoin(_ histories: [AddressBalanceHistory]) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        for t in trades(histories) {
            out[t.coin] = (out[t.coin] ?? 0) + (t.type == .DEPOSIT ? t.amountExact : -t.amountExact)
        }
        return out.filter { $0.value > 0 }
    }
}
