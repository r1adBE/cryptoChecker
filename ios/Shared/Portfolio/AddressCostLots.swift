import Foundation

// Eröffnungsposten (Einstand) für Guthaben beobachteter Adressen — wie `AddressCostLots.kt`.

/// Eröffnungsposten einer beobachteten Adresse («Einstand für Adressguthaben»): der Nutzer nennt für einen Teil
/// des Guthabens Kaufdatum, Menge und Einstand. `id` ist innerhalb der Adresse eindeutig (1, 2, …), `coin` normalisiert,
/// `time` = Erwerb (ms), `amount` > 0. Einstand entweder als `priceUsdt` (je Coin) oder als `costUsdt` (gesamt) — so wie
/// eingegeben gespeichert, damit nichts durch Umrechnen rundet. JSON wie Android:
/// `{"id":1,"coin":"BTC","t":…,"amount":"0.5","price":"30000"}` («cost» statt «price», wenn gesamt eingegeben).
struct AddressCostLot: Codable, Equatable, Hashable, Sendable {
    var id: Int64
    var coin: String
    var time: Int64
    var amount: Decimal
    var priceUsdt: Decimal?
    var costUsdt: Decimal?

    init(id: Int64, coin: String, time: Int64, amount: Decimal, priceUsdt: Decimal? = nil, costUsdt: Decimal? = nil) {
        self.id = id
        self.coin = coin
        self.time = time
        self.amount = amount
        self.priceUsdt = priceUsdt
        self.costUsdt = costUsdt
    }

    /// Preis je Coin: erfasster Preis, sonst Einstand ÷ Menge (34 Stellen); nil ohne gültigen Einstand.
    var price: Decimal? {
        if let p = priceUsdt, p >= 0 { return p }
        if let c = costUsdt, c >= 0, amount > 0 { return PortfolioDecimal.divide(c, amount) }
        return nil
    }

    /// Einstand gesamt: erfasster Einstand, sonst Menge × Preis; nil ohne gültigen Einstand.
    var cost: Decimal? {
        if let c = costUsdt, c >= 0 { return c }
        if let p = priceUsdt, p >= 0 { return amount * p }
        return nil
    }

    private enum CodingKeys: String, CodingKey { case id, coin, t, amount, price, cost }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        coin = try c.decode(String.self, forKey: .coin)
        time = try c.decode(Int64.self, forKey: .t)
        guard let a = PortfolioDecimal.parse(try c.decode(String.self, forKey: .amount)) else {
            throw DecodingError.dataCorruptedError(forKey: .amount, in: c, debugDescription: "amount")
        }
        amount = a
        priceUsdt = PortfolioDecimal.parse((try? c.decodeIfPresent(String.self, forKey: .price)) ?? nil)
        costUsdt = PortfolioDecimal.parse((try? c.decodeIfPresent(String.self, forKey: .cost)) ?? nil)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(coin, forKey: .coin)
        try c.encode(time, forKey: .t)
        try c.encode(PortfolioDecimal.text(amount), forKey: .amount)
        if let p = priceUsdt {
            try c.encode(PortfolioDecimal.text(p), forKey: .price)
        } else if let cost = costUsdt {
            try c.encode(PortfolioDecimal.text(cost), forKey: .cost)
        }
    }
}

/// Regeln der Eröffnungsposten — wie `AddressCostLots` (Kotlin): Wallet = die Adresse; nie mehr als der Bestand vor den
/// in der App erfassten Bewegungen dieser Wallet (`recorded`: Guthaben + erfasste Abgänge − erfasste Zugänge; das Guthaben
/// enthält sie schon, gegen das nackte Guthaben gekürzt zählten sie doppelt). Darüber verbraucht FIFO die ältesten Posten
/// zuerst, Preis je Coin der übrigen bleibt; nur der nicht belegte Rest dieses Bestands bleibt Adress-Teil «ohne Einstand»;
/// «Nur anzeigen» und Adressen in einem xpub zählen nicht. Auch eine Adresse OHNE Posten verrechnet die erfassten
/// Bewegungen ihrer Wallet (eine Umbuchung zur Adresse steckt schon im Guthaben, zählt nur einmal). Zeiträume und Verlauf
/// lassen die Posten weg (Adress-Einträge, `AddressBalance.isAddressTrade`); Stichtag und CH-Vermögenswert rechnen wie die
/// Karte mit dem Guthaben am Stichtag (`AddressCutoff`).
enum AddressCostLots {

    /// Höchstens so viele Posten je Adresse.
    static let maxLots = 50

    /// Ids der Posten im Rechner: ab hier abwärts (weit unter den Adress-Einzahlungen −1 … −1001 …).
    static let idBase: Int64 = -1_000_000_000

    /// Rechner-Eintrag eines Postens (Kauf mit negativer Id)?
    static func isLotTrade(_ id: Int64) -> Bool { id <= idBase }

    /// Id des Postens Nr. `index` (Reihenfolge von `clean`) der Adresse `addressId`: eindeutig, nie gespeichert.
    static func tradeId(addressId: Int64, index: Int) -> Int64 { idBase - addressId * 1000 - Int64(index) }

    /// Wallet der Adresse für FIFO je Wallet: Bezeichnung, sonst gekürzte Adresse.
    static func walletName(_ a: WatchedAddress) -> String { a.label ?? WatchedAddressRules.shortAddress(a.address) }

    /// Gültige Posten (Coin, Menge > 0, Einstand ≥ 0, Zeit > 0) nach Zeit, dann Id; höchstens `maxLots`.
    static func clean(_ lots: [AddressCostLot]) -> [AddressCostLot] {
        let valid: [AddressCostLot] = lots.compactMap { l in
            let coin = PortfolioCalculator.normalizeCoin(l.coin)
            let okCoin = !coin.isEmpty && coin.count <= 15 && coin.allSatisfy { $0.isLetter || $0.isNumber || $0 == "." }
            guard okCoin, l.time > 0, l.amount > 0, PortfolioDecimal.withinLimits(l.amount), l.price != nil else { return nil }
            var out = l
            out.coin = coin
            return out
        }
        return Array(valid.sorted { ($0.time, $0.id) < ($1.time, $1.id) }.prefix(maxLots))
    }

    /// Nächste freie Id einer Adresse.
    static func nextId(_ lots: [AddressCostLot]) -> Int64 { (lots.map(\.id).max() ?? 0) + 1 }

    /// Guthaben je Coin EINER Adresse, wie `AddressBalance.holdings` es zählt: Netz-Coin aus dem letzten Guthaben (auch 0),
    /// dazu die weiteren Guthaben > 0, derselbe Coin addiert. Ohne geladenes Guthaben fehlt der Coin.
    static func balances(_ a: WatchedAddress) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        if let b = PortfolioDecimal.parse(a.lastBalance), b >= 0 { out[a.chain.coin] = b }
        for h in a.tokenHoldings() {
            guard let v = PortfolioDecimal.parse(h.amount) else { continue }
            out[h.coin] = (out[h.coin] ?? 0) + v
        }
        return out
    }

    /// Adressen, deren Guthaben zählt, mit ihren Guthaben je Coin — gleiche Auswahl wie `AddressBalance.holdings`.
    static func counted(_ addresses: [WatchedAddress]) -> [(address: WatchedAddress, balances: [String: Decimal])] {
        let list = addresses.filter(\.countInPortfolio)
        let covered = WatchedAddressRules.coveredByXpub(list)
        var seen: [WatchedAddress] = []
        var out: [(address: WatchedAddress, balances: [String: Decimal])] = []
        for a in list {
            if a.chain == .BTC && !a.isXpub && covered.contains(a.address) { continue }
            if seen.contains(where: { $0.chain == a.chain && WatchedAddressRules.sameAddress(a.chain, $0.address, a.address) }) {
                continue
            }
            seen.append(a)
            out.append((a, balances(a)))
        }
        return out
    }

    /// Ergebnis von `cover` für einen Coin einer Adresse.
    struct Coverage: Equatable {
        let coin: String
        /// Wirksame Posten (gekürzt, Preis je Coin unverändert), Reihenfolge von `clean`.
        let lots: [AddressCostLot]
        /// Belegte Menge = min(Summe der Posten, `base`).
        let lotted: Decimal
        /// Beobachtetes Guthaben (≥ 0).
        let balance: Decimal
        /// Posten über `base` (verbraucht); 0 = keine.
        let excess: Decimal
        /// Bestand vor den erfassten Bewegungen der Wallet (≥ 0, `recorded`); ohne solche Bewegungen = `balance`.
        let base: Decimal

        init(coin: String, lots: [AddressCostLot], lotted: Decimal, balance: Decimal, excess: Decimal, base: Decimal? = nil) {
            self.coin = coin
            self.lots = lots
            self.lotted = lotted
            self.balance = balance
            self.excess = excess
            self.base = base ?? balance
        }

        var capped: Bool { excess > 0 }
        /// Bestand ohne Posten («ohne Einstand»).
        var unlotted: Decimal { max(0, base - lotted) }
        /// Teil des beobachteten Guthabens, den Posten und erfasste Bewegungen schon abbilden (Rest = Adress-Teil ohne
        /// Einstand); ohne erfasste Bewegungen = `lotted`, negativ, wenn erfasste Abgänge auch Coins ohne Einstand aus der
        /// Adresse genommen haben.
        var accounted: Decimal { balance - unlotted }
    }

    /// Posten `lots` EINES Coins gegen den Bestand vor den erfassten Bewegungen: Guthaben `balance` − `recorded`
    /// (Zugänge − Abgänge dieser Wallet), nie unter 0. Was darüber liegt, verbraucht FIFO — die ältesten zuerst.
    static func cover(_ coin: String, _ lots: [AddressCostLot], balance: Decimal, recorded: Decimal = 0) -> Coverage {
        let observed = max(0, balance)
        let held = max(0, observed - recorded)
        let total = lots.reduce(Decimal(0)) { $0 + $1.amount }
        let excess = max(0, total - held)
        var left = excess
        var out: [AddressCostLot] = []
        for l in lots {
            if left >= l.amount {
                left -= l.amount
                continue
            }
            let rest = l.amount - left
            left = 0
            if rest == l.amount {
                out.append(l)
            } else {
                // Angebrochen: Preis je Coin bleibt (ein Gesamt-Einstand wird zum Preis, sonst passte er nicht zur Menge)
                out.append(AddressCostLot(id: l.id, coin: l.coin, time: l.time, amount: rest, priceUsdt: l.price, costUsdt: nil))
            }
        }
        return Coverage(coin: coin, lots: out, lotted: total - excess, balance: observed, excess: excess, base: held)
    }

    /// In der App erfasste Bewegungen von `coin` in der Wallet `wallet` (Vergleich `PortfolioWallets.key`): Zugänge −
    /// Abgänge, so wie FIFO je Wallet sie bucht. Alle Daten, nicht erst ab dem Posten: das Guthaben enthält jede Bewegung
    /// bis zu seinem Abruf (`fetched` wählt sie aus). Adress-Einträge zählen nicht; Wallet ohne Namen = keine Bewegungen.
    /// Wie Android.
    static func recorded(_ coin: String, wallet: String?, trades: [PortfolioTx]) -> Decimal {
        let key = PortfolioWallets.key(wallet)
        var net: Decimal = 0
        guard key != PortfolioWallets.unspecified else { return net }
        func at(_ name: String?) -> Bool { PortfolioWallets.key(name) == key }
        for t in trades where !AddressBalance.isAddressTrade(t.id) && t.amountExact > 0 {
            if PortfolioCalculator.normalizeCoin(t.coin) == coin {
                if t.type == .TRANSFER {
                    if at(t.transferTo) { net += t.amountExact }
                    if at(t.transferFrom) { net -= t.amountExact + t.coinFeeExact }
                } else if at(t.exchange) {
                    if t.type.isInflow { net += t.receivedExact } else { net -= t.amountExact + t.coinFeeExact }
                }
            }
            if t.swapToCoin == coin && at(t.exchange) { net += t.swapReceived }
            if t.thirdFeeCoin == coin && at(t.type == .TRANSFER ? t.transferFrom : t.exchange) { net -= t.thirdFeeAmount }
        }
        return net
    }

    /// Wallet → Id der zählenden Adresse, die die erfassten Bewegungen verrechnet: die erste mit Posten, sonst die erste
    /// ohne (gleiche Bezeichnung = in FIFO je Wallet eine Wallet; bei beiden gerechnet zählten sie doppelt). Wie Android.
    static func movesOwners(_ addresses: [WatchedAddress]) -> [String: Int64] {
        var out: [String: Int64] = [:]
        let list = counted(addresses)
        for (a, _) in list where !clean(a.costLots).isEmpty {
            let key = PortfolioWallets.key(walletName(a))
            if out[key] == nil { out[key] = a.id }
        }
        for (a, _) in list {
            let key = PortfolioWallets.key(walletName(a))
            if out[key] == nil { out[key] = a.id }
        }
        return out
    }

    /// Abdeckung je Coin (Reihenfolge der Posten); `trades` = erfasste Bewegungen, die diese Adresse verrechnet. Mit
    /// `allCoins` und Bewegungen auch jeder Coin des Guthabens ohne Posten — eine erfasste Umbuchung zur Adresse steckt
    /// schon im Guthaben und zählt so nur einmal. Wie Android.
    private static func covers(_ a: WatchedAddress, _ b: [String: Decimal], _ trades: [PortfolioTx],
                               allCoins: Bool = false) -> [Coverage] {
        let lots = clean(a.costLots)
        var list = coins(lots)
        if allCoins && !trades.isEmpty {
            for coin in b.keys.sorted() where !list.contains(coin) { list.append(coin) }
        }
        return list.map { coin in
            cover(coin, lots.filter { $0.coin == coin }, balance: b[coin] ?? 0,
                  recorded: recorded(coin, wallet: walletName(a), trades: trades))
        }
    }

    /// Bewegungen bis zum Abruf des Guthabens (`lastUpdated`; unbekannt = alle): nur sie stecken schon im Guthaben, eine
    /// spätere Buchung zählt allein als Buchung — wie der Stichtag (`AddressCutoff`) mit seiner Beobachtung. Wie Android.
    private static func fetched(_ a: WatchedAddress, _ trades: [PortfolioTx]) -> [PortfolioTx] {
        a.lastUpdated <= 0 ? trades : trades.filter { $0.time <= a.lastUpdated }
    }

    /// Zählende Adressen mit ihrer Abdeckung; erfasste Bewegungen je Wallet nur einmal (`movesOwners`).
    private static func countedCoverage(_ addresses: [WatchedAddress], _ trades: [PortfolioTx])
        -> [(address: WatchedAddress, coverage: [Coverage])] {
        let owners = trades.isEmpty ? [:] : movesOwners(addresses)
        return counted(addresses).map { entry in
            let a = entry.address
            let own = owners[PortfolioWallets.key(walletName(a))] == a.id
            return (a, covers(a, entry.balances, own ? fetched(a, trades) : [], allCoins: true))
        }
    }

    /// Coins der Posten in der Reihenfolge des ersten Auftretens.
    private static func coins(_ lots: [AddressCostLot]) -> [String] {
        var out: [String] = []
        for l in lots where !out.contains(l.coin) { out.append(l.coin) }
        return out
    }

    /// Je Coin mit Posten die Abdeckung dieser Adresse (Guthaben fehlt = 0); mit den echten Transaktionen `trades` und
    /// allen Adressen `all` wie im Rechner.
    static func coverage(_ a: WatchedAddress, trades: [PortfolioTx] = [], all: [WatchedAddress]? = nil) -> [Coverage] {
        let owner = trades.isEmpty ? nil : movesOwners(all ?? [a])[PortfolioWallets.key(walletName(a))]
        return covers(a, balances(a), owner == nil || owner == a.id ? fetched(a, trades) : [])
    }

    /// Rechner-Einträge der Posten aller zählenden Adressen: je wirksamem Posten ein Kauf mit negativer Id, Preis je Coin,
    /// Wallet = die Adresse. Reihenfolge der Adressen, dann der Posten. `real` = echte Transaktionen (erfasste Bewegungen
    /// der Adress-Wallets, `cover`).
    static func trades(_ addresses: [WatchedAddress], real: [PortfolioTx] = []) -> [PortfolioTx] {
        var out: [PortfolioTx] = []
        for (a, covered) in countedCoverage(addresses, real) {
            let lots = clean(a.costLots)
            for c in covered {
                let coin = c.coin
                for l in c.lots {
                    let index = lots.firstIndex(where: { $0.id == l.id }) ?? 0
                    out.append(PortfolioTx(id: tradeId(addressId: a.id, index: index), coin: coin, type: .BUY,
                                           amountExact: l.amount, priceExact: l.price, time: l.time,
                                           exchange: walletName(a)))
                }
            }
        }
        return out
    }

    /// Je Coin über alle zählenden Adressen der Teil des Guthabens, den Posten und erfasste Bewegungen schon abbilden
    /// (`Coverage.accounted`; ohne Bewegungen = belegte Menge, auch für Adressen ohne Posten) — der Rest ist «ohne
    /// Einstand» in `AddressBalance.trades`.
    /// Kann negativ sein. Wie Android.
    static func accounted(_ addresses: [WatchedAddress], real: [PortfolioTx] = []) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        for (_, covered) in countedCoverage(addresses, real) {
            for c in covered where c.accounted != 0 { out[c.coin] = (out[c.coin] ?? 0) + c.accounted }
        }
        return out
    }

    /// Steuer-Export: eigene Transaktionen + Eröffnungsposten als Käufe; FR dazu die beobachteten Guthaben-Reihen für den
    /// Gesamtwert — ohne den belegten Teil (`withoutLotted`, `accounted`), der schon als Posten zählt. Wie Android.
    static func exportTrades(_ real: [PortfolioTx], addresses: [WatchedAddress], frTotalValue: Bool) -> [PortfolioTx] {
        let lots = trades(addresses, real: real)
        guard frTotalValue else { return real + lots }
        return real + lots + withoutLotted(AddressBalanceHistory.trades(AddressBalanceHistory.of(addresses)),
                                           lotted: accounted(addresses, real: real))
    }

    /// Beobachtete Guthaben-Reihen ohne den belegten Teil (je Coin, ≤ 0 = nichts): die ältesten Zugänge werden gekürzt (bzw. fallen
    /// weg), Abgänge bleiben. Reihenfolge der Eingabe. Wie Android.
    static func withoutLotted(_ history: [PortfolioTx], lotted: [String: Decimal]) -> [PortfolioTx] {
        guard !lotted.isEmpty else { return history }
        var left = lotted
        var cut: [Int: Decimal] = [:]
        let order = history.indices.sorted { history[$0].time != history[$1].time ? history[$0].time < history[$1].time : $0 < $1 }
        for i in order {
            let t = history[i]
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            guard let open = left[coin], t.type == .DEPOSIT, open > 0 else { continue }
            let take = min(open, t.amountExact)
            left[coin] = open - take
            cut[i] = t.amountExact - take
        }
        return history.indices.compactMap { i in
            guard let rest = cut[i] else { return history[i] }
            guard rest > 0 else { return nil }
            var t = history[i]
            t.amountExact = rest
            return t
        }
    }

    /// Vorschlag Einstand gesamt = Menge × Tagesschluss (> 0); sonst nil.
    static func suggestion(amount: Decimal, close: Decimal?) -> Decimal? {
        guard let close, close > 0, amount > 0 else { return nil }
        return amount * close
    }

    /// Lesen aus der Sicherung (JSON-Objekte); unlesbare Einträge (auch ohne «id») fallen weg. Wie `fromJson`
    /// (Kotlin).
    static func lots(fromJSON raw: Any?) -> [AddressCostLot] {
        guard let array = raw as? [Any] else { return [] }
        var out: [AddressCostLot] = []
        for item in array {
            guard let o = item as? [String: Any], let id = (o["id"] as? NSNumber)?.int64Value,
                  let coin = o["coin"] as? String,
                  let time = (o["t"] as? NSNumber)?.int64Value,
                  let amount = PortfolioDecimal.parse(o["amount"] as? String) else { continue }
            out.append(AddressCostLot(id: id, coin: coin, time: time,
                                      amount: amount, priceUsdt: PortfolioDecimal.parse(o["price"] as? String),
                                      costUsdt: PortfolioDecimal.parse(o["cost"] as? String)))
        }
        return clean(out)
    }
}
