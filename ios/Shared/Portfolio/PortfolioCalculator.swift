import Foundation

/// Kennzahlen eines Coins. Beträge in USDT — wie `CoinPosition` (Android).
struct CoinPosition: Sendable, Equatable, Identifiable {
    let coin: String
    /// Gehaltene Menge nach allen Transaktionen (nie negativ).
    let holdings: Double
    /// Ø Kaufpreis = Einstand ÷ Menge mit Kaufpreis (nach der Methode); nil ohne solche Menge.
    let avgCost: Double?
    /// Einstand des Bestands («investiert»); nil, wenn bei einem Teil der Kaufpreis fehlt.
    let costBasis: Double?
    let currentPrice: Double?
    /// Bestand × aktueller Kurs; nil ohne Kurs.
    let value: Double?
    /// Unrealisierter Gewinn/Verlust («Seit Kauf») des Teils MIT Kaufpreis (Wert dieses Teils − sein Einstand); nil
    /// ohne Kurs oder ohne Teil mit Kaufpreis. Fehlt nur bei einem Teil der Kaufpreis: `unrealizedPartial`.
    let unrealized: Double?
    let unrealizedPercent: Double?
    /// Realisierter Gewinn/Verlust des BEKANNTEN Teils: Verkäufe mit Preis, anteilig für die Menge
    /// mit Kaufpreis (Ø über die Käufe mit Preis). Der Rest steht in `realizedUnknownAmount`.
    let realized: Double
    /// Ein Teil des Bestands hat keinen Kaufpreis («Preis fehlt»).
    let priceMissing: Bool
    /// Mindestens ein Verkauf war grösser als der Bestand und wurde gekappt.
    let oversold: Bool
    let tradeCount: Int
    /// Verkaufte Menge mit unbekanntem Gewinn/Verlust: Anteil ohne Kaufpreis an Verkäufen mit Preis
    /// sowie Verkäufe ohne Preis. Zählt nicht in `realized`; die Anzeige zeigt dafür «—».
    var realizedUnknownAmount: Double = 0
    /// Insgesamt verkaufte Menge (gekappt auf den Bestand) — für «geschlossen».
    var soldAmount: Double = 0
    /// `holdings` exakt (Anzeige der Menge ohne Rundung, z. B. 0.00000001 BTC).
    var holdingsExact: Decimal = 0
    /// `costBasis` exakt; nil wie dort.
    var costBasisExact: Decimal? = nil
    /// `realized` exakt.
    var realizedExact: Decimal = 0
    /// Menge MIT Kaufpreis — nur über sie geht `avgCost`. Kleiner als `holdingsExact`, wenn
    /// `priceMissing`: Dann nennt die Anzeige den Ø «von 0.5 BTC mit Kaufpreis».
    var pricedHoldingsExact: Decimal = 0
    /// `value` exakt: Bestand × kürzeste Darstellung des Kurses; nil ohne Kurs.
    var valueExact: Decimal? = nil
    /// `unrealized` exakt: Menge mit Kaufpreis × Kurs − `pricedCostExact`; nil wie dort.
    var unrealizedExact: Decimal? = nil
    /// Bei mindestens einer Transaktion dieses Coins fehlt der Preis der Gebühr in einem dritten Coin:
    /// Einstand bzw. Erlös ohne diese Gebühr (Anzeige «unvollständig»).
    var feeValueMissing = false
    /// Ein Verkauf mit Gebühr in einem dritten Coin ohne deren Preis — realisiert ohne diese Gebühr.
    var realizedFeeMissing = false
    /// Ausgezahlte Menge (`WITHDRAWAL`, gekappt auf den Bestand) — für «geschlossen».
    var withdrawnAmount: Double = 0
    /// Guthaben beobachteter Adressen (in `holdingsExact` und `valueExact` enthalten) — eigener Teil ohne
    /// Einstand: Einstand, Ø, «Seit Kauf», realisiert und Steuer rechnen nur mit den eigenen Transaktionen.
    var addressHoldingsExact: Decimal = 0
    /// Wert von `addressHoldingsExact`; nil ohne Kurs.
    var addressValueExact: Decimal? = nil
    /// Einstandsmethode, nach der gerechnet wurde.
    var method: CostMethod = .defaultMethod
    /// Einstand von Netzwerkgebühren, die den letzten Posten MIT Kaufpreis verbrauchten (übrig nur Bestand ohne
    /// Kaufpreis) — weder im Einstand noch realisiert; wie Android `lostFeeCostUsdt`. 0 = keiner.
    var lostFeeCostUsdt: Decimal = 0
    /// Umbuchungsgebühren (Anteil auf Bestand OHNE Kaufpreis), die keinem bekannten Einstand zugeschlagen werden können
    /// (`PortfolioUnassignedFee`) — weder im Einstand noch realisiert; Anzeige «Gebühr nicht zugeordnet – Einstand
    /// unbekannt», Steuer-Export: Info-Zeile. Wie Android `unassignedFeeUsdt`. 0 = keine.
    var unassignedFeeUsdt: Decimal = 0
    /// Mindestens eine Veräusserung hat einen Teil ohne Einstand oder ohne Erlös (z. B. Gebühr im Coin aus einem Posten
    /// ohne Kaufpreis, ungedeckter Rest) bzw. ohne Wert der Gebühr — dieselben Daten, die der Steuer-Export als
    /// «unvollständig» markiert. Wie Android `disposalsIncomplete`.
    var disposalsIncomplete = false
    /// Einstand des Teils MIT Kaufpreis (`pricedHoldingsExact`) — Basis von `unrealized`/`unrealizedPercent`; gleich
    /// `costBasisExact`, wenn kein Teil ohne Kaufpreis ist. nil ohne eigenen Teil mit Kaufpreis. Wie Android.
    var pricedCostExact: Decimal? = nil

    var id: String { coin }
    /// Eigener Bestand OHNE Kaufpreis (nicht in «Seit Kauf»); 0 = keiner.
    var unpricedHoldingsExact: Decimal { max(0, ledgerHoldingsExact - pricedHoldingsExact) }
    /// «Seit Kauf» gilt nur für den Teil mit Kaufpreis — Anzeige «ohne x COIN ohne Kaufpreis» (`unpricedHoldingsExact`)
    /// und «unvollständig».
    var unrealizedPartial: Bool { unrealizedExact != nil && priceMissing && unpricedHoldingsExact > 0 }
    /// Bestand aus den eigenen Transaktionen (ohne Adress-Guthaben) — nur er hat Einstand und Posten.
    var ledgerHoldingsExact: Decimal { max(0, holdingsExact - addressHoldingsExact) }
    /// Ein Teil (oder alles) stammt aus beobachteten Adressen.
    var hasAddressPart: Bool { addressHoldingsExact > 0 }
    /// Ø gilt nur für einen Teil des Bestands (ein Teil ohne Kaufpreis) — Anzeige «Ø von … mit Kaufpreis».
    var avgCostPartial: Bool { avgCost != nil && priceMissing && pricedHoldingsExact > 0 }
    var isOpen: Bool { holdingsExact > 0 || (holdingsExact == 0 && holdings > PortfolioCalculator.eps) }
    /// Ein Teil des realisierten Gewinns/Verlusts ist unbekannt (`realizedUnknownAmount`, `realizedFeeMissing`,
    /// `disposalsIncomplete`) — gleiche Regel wie «unvollständig» im Steuer-Export.
    var realizedIncomplete: Bool {
        realizedUnknownAmount > PortfolioCalculator.eps || realizedFeeMissing || disposalsIncomplete
    }
}

/// Gesamtsicht über alle Coins — wie `PortfolioSummary` (Android).
struct PortfolioSummary: Sendable, Equatable {
    /// Coins mit Bestand, nach Wert absteigend.
    let open: [CoinPosition]
    /// Coins ohne Bestand, die verkauft wurden oder realisierten Gewinn/Verlust haben (auch 0/unbekannt).
    let closed: [CoinPosition]
    /// Summe der Werte (Coins ohne Kurs zählen nicht mit).
    let totalValue: Double
    /// Einstand der offenen Positionen mit Kaufpreis; nil, wenn keine einen hat.
    let invested: Double?
    /// Gewinn seit Kauf (unrealisiert) über die offenen Coins mit Teil MIT Kaufpreis UND aktuellem Kurs — je Coin
    /// nur dieser Teil (`CoinPosition.unrealized`); Coins ganz ohne Kaufpreis (`missingCostCoins`), Teile ohne
    /// Kaufpreis (`partialCostParts`) oder Coins ohne Kurs (`missingCurrentPrices`) zählen nicht mit («unvollständig»).
    /// nil, wenn kein Coin beides hat. `unrealizedPercent` auf den Einstand dieser Teile.
    let unrealized: Double?
    let unrealizedPercent: Double?
    let realized: Double
    /// Bei mindestens einem offenen Coin fehlt ein Kaufpreis.
    let costMissing: Bool
    /// Offene Coins ohne aktuellen Kurs.
    let missingCurrentPrices: [String]
    /// Bei mindestens einem Coin ist ein Teil des realisierten Gewinns/Verlusts unbekannt.
    var realizedIncomplete: Bool = false
    /// Offene Coins ganz ohne Kaufpreis (kein eigener Teil mit Kaufpreis) — fehlen in `unrealized` und `invested`.
    var missingCostCoins: [String] = []
    /// `invested` exakt; nil wie dort.
    var investedExact: Decimal? = nil
    /// `realized` exakt.
    var realizedExact: Decimal = 0
    /// Coins mit Gebühr in einem dritten Coin ohne deren Preis (`CoinPosition.feeValueMissing`), alphabetisch.
    var feeValueMissingCoins: [String] = []
    /// Wert der Adress-Guthaben (in `totalValue` enthalten; Coins ohne Kurs zählen nicht).
    var addressValue: Double = 0
    /// Einstandsmethode, nach der gerechnet wurde.
    var method: CostMethod = .defaultMethod
    /// Wertänderung ausgebuchter Coins bis zur Auszahlung (`PortfolioCalculator.withdrawnGain`, bekannter Teil): steckt
    /// in der Gewinn/Verlust-Kurve des Wertverlaufs, nicht in «Seit Kauf»/«realisiert». nil = keine Auszahlung mit
    /// bekanntem Teil. Karte: «Ausgebucht: +X» nur, wenn ≠ 0. Wie Android.
    var withdrawnGainExact: Decimal? = nil
    /// Bei mindestens einer Auszahlung fehlt ein Teil davon (Kaufpreis, Kurs oder Wert der Gebühr).
    var withdrawnGainIncomplete = false
    /// Offene Coins, bei denen nur ein TEIL ohne Kaufpreis ist: «Seit Kauf» zählt den Teil mit Kaufpreis, der Rest
    /// fehlt (Hinweis «ohne 0.5 BTC ohne Kaufpreis»). In `invested` fehlen diese Coins. Wie Android.
    var partialCostParts: [PortfolioUnpricedPart] = []
    /// Summe der nicht zugeordneten Umbuchungsgebühren aller Coins (`CoinPosition.unassignedFeeUsdt`) — für den Hinweis
    /// «Gebühr nicht zugeordnet – Einstand unbekannt»; 0 = keine. Wie Android.
    var unassignedFeeUsdt: Decimal = 0

    var isEmpty: Bool { open.isEmpty && closed.isEmpty }
}

/// Eigener Bestand ohne Kaufpreis eines Coins, der sonst einen Teil mit Kaufpreis hat (`PortfolioSummary.partialCostParts`).
struct PortfolioUnpricedPart: Sendable, Equatable {
    let coin: String
    let amount: Decimal
}

/// Buchhaltung je Coin nach der Einstandsmethode `CostMethod`: FIFO (Standard) in `PortfolioFifo`; Durchschnittskosten
/// (`.AVERAGE`, «Ø») — Regeln exakt wie `PortfolioCalculator.kt`:
///  - Transaktionen je Coin zeitlich (bei Gleichstand nach Id) abarbeiten — dazu die Gebühren ANDERER
///    Transaktionen, die in diesem Coin bezahlt wurden (`PortfolioLedger.events`, «Gebühr-Abgang»).
///  - Gerechnet wird exakt mit `Decimal` (`PortfolioDecimal`): Bestand, Einstand, Gebühren und
///    realisierter Gewinn summieren sich ohne Gleitkomma-Fehler (1 Satoshi = 0.00000001 BTC bleibt
///    1 Satoshi). Nur Teilungen (Ø, anteiliger Verkauf) runden.
///  - Kauf, Belohnung, Einzahlung mit Preis: Einstand += Menge × Preis + Gebühr USDT + Wert der Gebühr im
///    dritten Coin, Bestand mit Preis += erhaltene Menge (Menge − Gebühr im Coin). Ø = Einstand / Bestand
///    mit Preis. Ohne Preis erhöht nur den Bestand (Teil «ohne Preis»).
///  - Verkauf: Bestand sinkt um Menge + Gebühr im Coin (nie unter 0; ein Verkauf grösser als der
///    Bestand wird gekappt und markiert). Beide Teile (mit/ohne Preis) sinken anteilig, der Ø bleibt.
///    Realisiert += Anteil MIT Kaufpreis × (Menge × Verkaufspreis − Gebühr USDT − Wert der Gebühr im
///    dritten Coin) − Einstand des abgegebenen Teils, wenn der Verkaufspreis bekannt ist. Der Anteil ohne
///    Kaufpreis (und Verkäufe ohne Preis) hat unbekannten Gewinn/Verlust (`CoinPosition.realizedUnknownAmount`).
///  - Auszahlung: wie ein Verkauf abgegeben (anteilig, Einstand zum Ø), aber KEIN realisierter Gewinn. Gebühr USDT +
///    Wert der Gebühr im dritten Coin: realisierter Aufwand (`PortfolioFeeExpenses`, Steuer-Export «Gebühr Auszahlung»).
///  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung, erhaltener Coin eines Tauschs): kein
///    Bestand, der Einstand bzw. Wert des Tauschs ist realisierter Aufwand (`PortfolioFeeExpenses`).
///  - Umbuchung: Menge und Einstand bleiben; Gebühr im Coin senkt nur die Menge (Ø steigt); Gebühr USDT +
///    Wert der Gebühr im dritten Coin erhöhen den Einstand des Teils mit Kaufpreis (Anteil mit Preis / Bestand).
///  - Gebühr-Abgang im Gebühr-Coin: wie eine Veräusserung zum Gebühr-Preis; ohne Preis «unbekannter Gewinn».
///  - Gebühr im dritten Coin ohne Preis: keine Gebühr in Einstand/Erlös, markiert (`feeValueMissing`,
///    beim Verkauf auch `realizedFeeMissing`).
///  - Gewinn/Verlust je Coin nur, wenn der ganze Restbestand einen Kaufpreis hat. Die Summe
///    (`PortfolioSummary.unrealized`) zählt nur Coins mit Einstand und Kurs.
///  - Aktueller Kurs nach der Stablecoin-Regel (`PortfolioStables`): USDT immer 1, andere
///    Stablecoins ihr Marktkurs, ohne Kurs kein Wert (wie jeder Coin: «—», «ohne Kurs»).
enum PortfolioCalculator {
    /// Darunter gilt eine Menge (Double) als null.
    static let eps = 1e-12

    static func normalizeCoin(_ coin: String) -> String {
        coin.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// Ergebnis von `take`: abgegebene Menge, Anteil mit Kaufpreis davor, abgegebener Einstand samt Herkunft.
    fileprivate struct Taken {
        let removed: Decimal
        let allPriced: Bool
        let share: Decimal
        let costRemoved: Decimal
        let removedPriced: Decimal
        let partsRemoved: [PortfolioCostPart]
    }

    /// Bestand eines Coins während der Rechnung (Teil mit/ohne Kaufpreis, Einstand samt Herkunft).
    fileprivate struct Book {
        var priced: Decimal = 0      // Menge mit Kaufpreis
        var unpriced: Decimal = 0    // Menge ohne Kaufpreis
        var cost: Decimal = 0        // Einstand des Teils mit Kaufpreis (inkl. Gebühren in USDT)
        var parts: [PortfolioCostPart] = [] // Herkunft von `cost`

        var holdings: Decimal { priced + unpriced }

        mutating func addCost(_ time: Int64, _ usdt: Decimal) {
            cost += usdt
            if usdt != 0 { parts.append(PortfolioCostPart(time: time, usdt: usdt)) }
        }

        /// Einstand und Herkunft ganz verwerfen (Teil mit Kaufpreis ist leer).
        mutating func resetPriced() {
            priced = 0
            cost = 0
            parts = []
        }

        /// Nimmt `removed` von `before` (Einstand davor) anteilig aus allen Herkunftsteilen; Summe exakt `removed`.
        mutating func takeParts(_ removed: Decimal, before: Decimal) -> [PortfolioCostPart] {
            guard removed != 0, !parts.isEmpty else { return [] }
            if removed >= before {
                let all = parts
                parts = []
                return all
            }
            var out: [PortfolioCostPart] = []
            var left = removed
            for i in parts.indices {
                let p = parts[i]
                let r = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt * removed, before)
                left -= r
                out.append(PortfolioCostPart(time: p.time, usdt: r))
                parts[i] = PortfolioCostPart(time: p.time, usdt: p.usdt - r)
            }
            return out
        }

        /// Gibt `requested` ab (gekappt auf den Bestand > 0), beide Teile anteilig samt Einstand — wie `take` (Android).
        mutating func take(_ requested: Decimal) -> Taken {
            let total = holdings
            let removed = min(requested, total)
            let allPriced = unpriced == 0
            // Teilungen und anteilige Produkte auf 34 Stellen wie Android (`PortfolioDecimal.divide`)
            let share: Decimal = allPriced ? 1 : PortfolioDecimal.divide(priced, total)
            let removedPriced = allPriced ? removed : min(PortfolioDecimal.amount(PortfolioDecimal.multiply(removed, share)), priced)
            let costRemoved: Decimal
            if priced == 0 {
                costRemoved = 0
            } else if removedPriced >= priced {
                costRemoved = cost
            } else {
                costRemoved = PortfolioDecimal.divide(cost * removedPriced, priced)
            }
            let partsRemoved = takeParts(costRemoved, before: cost)
            // Gesamtbestand bleibt exakt; der Teil ohne Preis ist der Rest
            let rest = total - removed
            priced -= removedPriced
            cost -= costRemoved
            if PortfolioDecimal.isDust(priced) || priced < 0 { resetPriced() }
            unpriced = max(0, rest - priced)
            if PortfolioDecimal.isDust(unpriced) { unpriced = 0 }
            return Taken(removed: removed, allPriced: allPriced, share: share, costRemoved: costRemoved,
                         removedPriced: removedPriced, partsRemoved: partsRemoved)
        }
    }

    /// Kennzahlen eines Coins aus seinen Transaktionen (fremde Coins zählen nur mit ihren Gebühren in diesem Coin)
    /// nach `method`. Adress-Guthaben (`AddressBalance.isAddressTrade`) zählen nur als eigener Teil mit
    /// (`CoinPosition.addressHoldingsExact`).
    static func position(_ coin: String, trades: [PortfolioTx], currentPrice: Double?,
                         method: CostMethod = .defaultMethod) -> CoinPosition {
        ledger(coin, trades: trades, currentPrice: currentPrice, method: method).position
    }

    /// Wie `position`, dazu offene Posten (nur FIFO) und Veräusserungen für den Steuer-Export. Adress-Einträge
    /// (negative Id, `AddressBalance.withAddresses`) sind KEINE Posten — ihr Guthaben steht als eigener Teil in
    /// `CoinPosition.addressHoldingsExact`: Menge und Wert zählen mit, Einstand, Ø, «Seit Kauf», realisiert und
    /// Steuer nur aus den eigenen Transaktionen. Wie Android.
    /// `feePrice`: Marktkurs für Gebühr-Zeilen (Tageskurs, wie `summarize` `withdrawalPrice`); nil = Erlös unbekannt.
    static func ledger(_ coin: String, trades: [PortfolioTx], currentPrice: Double?,
                       method: CostMethod = .defaultMethod,
                       feePrice: ((String, Int64) -> Double?)? = nil) -> CoinLedger {
        let symbol = normalizeCoin(coin)
        let split = AddressBalance.split(feeValued(trades, feePrice))
        return ledgerOf(symbol, events: PortfolioLedger.events(symbol, trades: split.real),
                        addressAmount: split.address[symbol], currentPrice: currentPrice, method: method)
    }

    /// Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs `price` (> 0, endlich), sonst
    /// unbekannt (`PortfolioLedger.valueAtDayClose`).
    private static func feeValued(_ trades: [PortfolioTx], _ price: ((String, Int64) -> Double?)?) -> [PortfolioTx] {
        var exact: ((String, Int64) -> Decimal?)? = nil
        if let f = price {
            exact = { coin, time in
                guard let v = f(coin, time), v > 0, v.isFinite else { return nil }
                return PortfolioDecimal.of(v)
            }
        }
        return PortfolioLedger.valueAtDayClose(trades, price: exact)
    }

    /// `ledger` aus den schon gesammelten Schritten `events` des Coins (ohne Adress-Einträge).
    private static func ledgerOf(_ symbol: String, events: [PortfolioLedgerEvent], addressAmount: Decimal?,
                                 currentPrice: Double?, method: CostMethod) -> CoinLedger {
        let book: PortfolioBookResult
        switch method {
        case .FIFO: book = PortfolioFifo.run(symbol, events: events)
        case .FIFO_WALLET: book = PortfolioFifo.run(symbol, events: events, perWallet: true)
        case .AVERAGE: book = average(symbol, events: events)
        }
        let priced = book.priced
        let unpriced = book.unpriced
        let cost = book.cost
        let total = priced + unpriced
        let ledgerHoldings: Decimal = PortfolioDecimal.isDust(total) ? 0 : total
        let addressHoldings: Decimal = max(0, addressAmount ?? 0)
        let holdings = ledgerHoldings + addressHoldings
        // Stablecoin-Regel: USDT = 1, andere Stablecoins Marktkurs, sonst kein Wert (`PortfolioStables`)
        let price = PortfolioStables.price(symbol, market: currentPrice)
        let ledgerOpen = ledgerHoldings > 0
        let priceMissing = ledgerOpen && unpriced > 0
        let avgCost: Double? = priced > 0 ? PortfolioDecimal.double(PortfolioDecimal.divide(cost, priced)) : nil
        let costBasisExact: Decimal? = ledgerOpen && !priceMissing ? cost : nil
        let costBasis = costBasisExact.map(PortfolioDecimal.double)
        // Einstand des Teils mit Kaufpreis: «Seit Kauf» auch dann, wenn nur ein Teil ohne Kaufpreis ist
        let pricedHoldings: Decimal = ledgerOpen ? priced : 0
        let pricedCostExact: Decimal? = pricedHoldings > 0 ? cost : nil
        // Wert und unrealisierter Gewinn exakt (Kurs über seine kürzeste Darstellung); Double nur für die Anzeige.
        // Wert = ganzer Bestand (mit Adressen), «Seit Kauf» nur auf den eigenen Teil mit Kaufpreis.
        let priceExact: Decimal? = price.flatMap { PortfolioDecimal.of($0) }
        let valueExact: Decimal? = priceExact.map { holdings * $0 }
        let value = valueExact.map(PortfolioDecimal.double)
        // Ganz mit Kaufpreis: ganzer eigener Bestand (wie bisher, Staub inklusive), sonst nur der Teil mit Kaufpreis
        let pricedBase: Decimal = priceMissing ? pricedHoldings : ledgerHoldings
        let pricedValueExact: Decimal? = priceExact.map { pricedBase * $0 }
        var unrealizedExact: Decimal?
        if let pricedCostExact, let pricedValueExact { unrealizedExact = pricedValueExact - pricedCostExact }
        let unrealized = unrealizedExact.map(PortfolioDecimal.double)
        var unrealizedPercent: Double?
        if let unrealizedExact, let pricedCostExact, pricedCostExact > 0 {
            unrealizedPercent = PortfolioDecimal.double(PortfolioDecimal.divide(unrealizedExact, pricedCostExact)) * 100
        }

        let position = CoinPosition(
            coin: symbol,
            holdings: PortfolioDecimal.double(holdings),
            avgCost: avgCost,
            costBasis: costBasis,
            currentPrice: price,
            value: value,
            unrealized: unrealized,
            unrealizedPercent: unrealizedPercent,
            realized: PortfolioDecimal.double(book.realized),
            priceMissing: priceMissing,
            oversold: book.oversold,
            tradeCount: events.filter { !$0.feeLeg }.count,
            realizedUnknownAmount: PortfolioDecimal.isDust(book.unknownSold) ? 0 : PortfolioDecimal.double(book.unknownSold),
            soldAmount: PortfolioDecimal.isDust(book.soldTotal) ? 0 : PortfolioDecimal.double(book.soldTotal),
            holdingsExact: holdings,
            costBasisExact: costBasisExact,
            realizedExact: book.realized,
            pricedHoldingsExact: pricedHoldings,
            valueExact: valueExact,
            unrealizedExact: unrealizedExact,
            feeValueMissing: book.feeValueMissing,
            realizedFeeMissing: book.realizedFeeMissing,
            withdrawnAmount: PortfolioDecimal.isDust(book.withdrawn) ? 0 : PortfolioDecimal.double(book.withdrawn),
            addressHoldingsExact: addressHoldings,
            addressValueExact: priceExact.map { addressHoldings * $0 },
            method: method,
            lostFeeCostUsdt: book.lostFeeCosts.reduce(Decimal(0)) { $0 + $1.costUsdt },
            unassignedFeeUsdt: book.unassignedFees.reduce(Decimal(0)) { $0 + $1.usdt },
            // Aus denselben Veräusserungen wie der Steuer-Export: ein Teil ohne Einstand/Erlös → unvollständig
            disposalsIncomplete: book.disposals.contains { $0.costUsdt == nil || $0.proceedsUsdt == nil || $0.feeValueMissing },
            pricedCostExact: pricedCostExact
        )
        // Offene Posten für die Anzeige: FIFO je Posten, Ø zusammengefasst (Teil mit / ohne Kaufpreis) — wie Android
        var openLots: [PortfolioOpenLot] = []
        if method.isFifo {
            openLots = book.lots.map {
                PortfolioOpenLot(coin: symbol, txId: $0.txId, acquiredTime: $0.acquiredTime, kind: $0.kind, amount: $0.amount,
                                 costUsdt: $0.costUsdt, wallet: $0.wallet, aggregated: false)
            }
        } else if ledgerOpen {
            if priced > 0 {
                openLots.append(PortfolioOpenLot(coin: symbol, txId: nil, acquiredTime: nil, kind: nil, amount: priced,
                                                 costUsdt: cost, wallet: nil, aggregated: true))
            }
            if unpriced > 0 {
                openLots.append(PortfolioOpenLot(coin: symbol, txId: nil, acquiredTime: nil, kind: nil, amount: unpriced,
                                                 costUsdt: nil, wallet: nil, aggregated: true))
            }
        }
        return CoinLedger(position: position, lots: book.lots, disposals: book.disposals, withdrawals: book.withdrawals,
                          openLots: openLots, walletShortfalls: book.shortfalls, lostFeeCosts: book.lostFeeCosts,
                          unassignedFees: book.unassignedFees)
    }

    /// Offene Posten eines Coins nach `method` (`PortfolioOpenLot`): FIFO und FIFO je Wallet die tatsächlichen Posten, Ø
    /// zusammengefasst. Ohne Adress-Guthaben. Wie `openLots` (Android).
    static func openLots(_ coin: String, trades: [PortfolioTx], method: CostMethod = .defaultMethod) -> [PortfolioOpenLot] {
        ledger(coin, trades: trades, currentPrice: nil, method: method).openLots
    }

    /// `.FIFO_WALLET`: alle Abgänge über den Bestand ihrer Wallet, zeitlich, dann Id, Coin; leer bei anderen Methoden.
    /// Wie `walletShortfalls` (Android).
    static func walletShortfalls(_ trades: [PortfolioTx], method: CostMethod = .FIFO_WALLET) -> [PortfolioWalletShortfall] {
        guard method == .FIFO_WALLET else { return [] }
        let real = AddressBalance.split(trades).real
        let all = PortfolioLedger.eventsByCoin(real).flatMap { entry in
            PortfolioFifo.run(entry.coin, events: entry.events, perWallet: true).shortfalls
        }
        // Stabil sortiert wie Android (`sortedWith`)
        return all.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.time != y.time { return x.time < y.time }
            if x.txId != y.txId { return x.txId < y.txId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Eigener Bestand je Coin (Grossschreibung) aus den Transaktionen mit Zeit ≤ `time` nach `method` — dieselben Mengen
    /// wie `ledgerHoldingsExact` der Karte (`PortfolioHoldingsBook`: FIFO je Wallet kappt Abgänge auf die Wallet). Adress-
    /// Einträge zählen nicht; Staub (`PortfolioDecimal.isDust`) = 0. Nur Coins mit Bestand > 0, Reihenfolge wie
    /// `PortfolioLedger.coins`. Wie Android.
    static func holdingsAt(_ trades: [PortfolioTx], time: Int64 = .max,
                           method: CostMethod = .defaultMethod) -> [(coin: String, amount: Decimal)] {
        let real = trades.filter { $0.time <= time && !AddressBalance.isAddressTrade($0.id) }
        var out: [(coin: String, amount: Decimal)] = []
        for entry in PortfolioLedger.eventsByCoin(real) {
            var book = PortfolioHoldingsBook(method: method)
            for e in entry.events { book.apply(e) }
            if book.total > 0 && !PortfolioDecimal.isDust(book.total) { out.append((coin: entry.coin, amount: book.total)) }
        }
        return out
    }

    /// Alle Veräusserungen (Steuer-Export) nach `method`: zeitlich (Verkauf), dann Id des Verkaufs, Coin; je Verkauf
    /// die Posten in FIFO-Reihenfolge. Adress-Guthaben kommen nicht vor. Wie Android.
    static func disposals(_ trades: [PortfolioTx], method: CostMethod = .defaultMethod) -> [PortfolioDisposal] {
        let real = AddressBalance.split(trades).real
        // Schritte je Coin einmal gesammelt (nicht je Coin über alle Transaktionen)
        let byCoin = PortfolioLedger.eventsByCoin(real)
        let all = byCoin.flatMap { entry in
            ledgerOf(entry.coin, events: entry.events, addressAmount: nil, currentPrice: nil, method: method).disposals
        }
        // Stabil sortiert wie Android (`sortedWith`)
        return all.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            if x.sellTime != y.sellTime { return x.sellTime < y.sellTime }
            if x.sellTxId != y.sellTxId { return x.sellTxId < y.sellTxId }
            if x.coin != y.coin { return x.coin < y.coin }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Wertänderung ausgebuchter Coins bis zur Auszahlung: bekannter Teil und ob etwas fehlt — wie Android.
    struct WithdrawnGain: Equatable {
        let amount: Decimal
        let incomplete: Bool
        let count: Int
    }

    /// Wertänderung ausgebuchter Coins: je Auszahlung (Wert beim Abgang − Einstand der verbrauchten Posten bzw. zum Ø).
    /// Wert beim Abgang = ausgezahlte Menge × Preis der Auszahlung (sonst `marketPrice`, Kurs zum Zeitpunkt). Gebühr USDT
    /// und Wert der Gebühr im dritten Coin zählen NICHT hier, sondern als realisierter Aufwand (`PortfolioFeeExpenses`);
    /// zusammen ergibt das den Abfluss im Wertverlauf. Nur der Teil mit Kaufpreis zählt (Wert anteilig); fehlt ein Teil
    /// oder ein Kurs: `incomplete`. Wie `withdrawnGain` (Android).
    static func withdrawnGain(_ withdrawals: [PortfolioWithdrawalCost],
                              marketPrice: ((String, Int64) -> Double?)? = nil) -> WithdrawnGain {
        var sum: Decimal = 0
        var incomplete = false
        var count = 0
        for w in withdrawals where w.removed > 0 {
            count += 1
            let t = w.trade
            var known = t.validPriceExact
            if known == nil, let market = marketPrice?(normalizeCoin(t.coin), t.time), market >= 0, market.isFinite {
                known = PortfolioDecimal.of(market)
            }
            guard let price = known, w.pricedRemoved > 0 else {
                incomplete = true
                continue
            }
            if w.pricedRemoved < w.removed { incomplete = true }
            // Gebühren: realisierter Aufwand, nicht hier
            let value: Decimal = w.sold * price
            let pricedValue = w.pricedRemoved == w.removed ? value
                : PortfolioDecimal.divide(value * w.pricedRemoved, w.removed)
            sum += pricedValue - w.cost
        }
        return WithdrawnGain(amount: sum, incomplete: incomplete, count: count)
    }

    /// Durchschnittskosten-Methode (siehe oben), mit einer Veräusserung je Verkauf (Teil mit/ohne Kaufpreis, dazu der
    /// ungedeckte Rest bei «mehr verkauft als erfasst»). Der Einstand wird zusätzlich nach Herkunft geführt
    /// (`PortfolioCostPart`); ein Abgang nimmt alle Teile anteilig mit — so rechnet der Steuer-Export den Ø-Einstand zum
    /// Devisenkurs JE Zugang um. Wie Android.
    private static func average(_ symbol: String, events: [PortfolioLedgerEvent]) -> PortfolioBookResult {
        var book = Book()
        var realized: Decimal = 0
        var unknownSold: Decimal = 0 // verkaufte Menge mit unbekanntem Gewinn/Verlust
        var soldTotal: Decimal = 0
        var withdrawn: Decimal = 0
        var oversold = false
        var feeValueMissing = false
        var realizedFeeMissing = false
        var disposals: [PortfolioDisposal] = []
        var withdrawals: [PortfolioWithdrawalCost] = []
        var lostFeeCosts: [PortfolioLostFeeCost] = []
        var unassignedFees: [PortfolioUnassignedFee] = []

        /// Veräusserungen eines Abgangs: Teil mit Kaufpreis (Einstand zum Ø), Teil ohne Kaufpreis und der ungedeckte
        /// Rest `uncovered`. `proceeds` = Erlös des GANZEN Abgangs; `coveredProceeds` = Anteil des gedeckten Teils.
        func record(_ t: PortfolioTx, _ taken: Taken?, hadPriced: Bool, proceeds: Decimal?, coveredProceeds: Decimal?,
                    uncovered: Decimal, feeLeg: Bool, feeMissing: Bool, swap: Bool = false) {
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            let gift = !feeLeg && t.fromGift
            let payment = !feeLeg && t.type == .PAYMENT && !t.fromGift
            let estimated = !feeLeg && t.valueEstimated
            func add(_ amount: Decimal, cost: Decimal?, proceeds part: Decimal?, gain: Decimal?,
                     costParts: [PortfolioCostPart] = [], isUncovered: Bool = false) {
                guard amount > 0 else { return }
                disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time, lotTxId: nil, acquiredTime: nil, lotKind: nil,
                    amount: amount, costUsdt: cost, proceedsUsdt: part, gainUsdt: gain, holdingDays: nil,
                    feeLeg: feeLeg, feeValueMissing: feeMissing, method: .AVERAGE,
                    costParts: costParts, uncovered: isUncovered, swap: swap, gift: gift, payment: payment, valueEstimated: estimated))
            }
            if let taken {
                if !hadPriced {
                    add(taken.removed, cost: nil, proceeds: coveredProceeds, gain: nil)
                } else {
                    let pricedProceeds = coveredProceeds.map { taken.allPriced ? $0 : PortfolioDecimal.multiply($0, taken.share) }
                    add(taken.removedPriced, cost: taken.costRemoved, proceeds: pricedProceeds,
                        gain: pricedProceeds.map { $0 - taken.costRemoved }, costParts: taken.partsRemoved)
                    if !taken.allPriced {
                        var rest: Decimal?
                        if let coveredProceeds, let pricedProceeds { rest = coveredProceeds - pricedProceeds }
                        add(taken.removed - taken.removedPriced, cost: nil, proceeds: rest, gain: nil)
                    }
                }
            }
            // Ungedeckter Rest: Erlös bekannt, Kaufpreis fehlt
            var restProceeds: Decimal?
            if let proceeds { restProceeds = coveredProceeds.map { proceeds - $0 } ?? proceeds }
            add(uncovered, cost: nil, proceeds: restProceeds, gain: nil, isUncovered: true)
        }

        /// Gebühr als Aufwand (`PortfolioFeeExpenses`): realisiert −Aufwand, eine Veräusserung mit Erlös 0.
        func expense(_ e: PortfolioLedgerEvent, _ kind: PortfolioFeeExpense, _ x: PortfolioFeeExpenses.Expense?) {
            guard let x else { return }
            realized -= x.usdt
            if x.feeValueMissing { feeValueMissing = true }
            disposals.append(PortfolioFeeExpenses.disposal(symbol, e, kind, x, method: .AVERAGE))
        }

        /// Erlösanteil des gedeckten Teils `removed` von `requested` (nach Menge).
        func coveredShare(_ proceeds: Decimal?, removed: Decimal, requested: Decimal) -> Decimal? {
            guard let proceeds else { return nil }
            if removed == requested { return proceeds }
            if removed <= 0 { return nil }
            return PortfolioDecimal.divide(proceeds * removed, requested)
        }

        for e in events {
            let t = e.trade
            let amount = t.amountExact
            guard amount > 0 else { continue }
            if e.swapIn {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); ohne Wert ohne Kaufpreis
                let received = t.swapReceived
                if received <= 0 {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                if let value = t.swapValue {
                    book.addCost(t.time, value)
                    book.priced += received
                } else {
                    book.unpriced += received
                }
                continue
            }
            if e.feeLeg {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                let fee = t.thirdFeeAmount
                if fee > book.holdings { oversold = true }
                let proceeds = t.thirdFeePrice.map { fee * $0 }
                if book.holdings <= 0 {
                    record(t, nil, hadPriced: false, proceeds: proceeds, coveredProceeds: nil, uncovered: fee,
                           feeLeg: true, feeMissing: false)
                    continue
                }
                let hadPriced = book.priced > 0
                let taken = book.take(fee)
                let covered = coveredShare(proceeds, removed: taken.removed, requested: fee)
                if let covered, hadPriced {
                    realized += (taken.allPriced ? covered : PortfolioDecimal.multiply(covered, taken.share)) - taken.costRemoved
                    if !taken.allPriced { unknownSold += PortfolioDecimal.multiply(taken.removed, 1 - taken.share) }
                } else {
                    unknownSold += taken.removed
                }
                record(t, taken, hadPriced: hadPriced, proceeds: proceeds, coveredProceeds: covered,
                       uncovered: fee - taken.removed, feeLeg: true, feeMissing: false)
                soldTotal += taken.removed
                continue
            }
            // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt (`PortfolioLedger.salePrice`)
            let price = PortfolioLedger.salePrice(t)
            let feeValue = t.thirdFeeValue
            switch t.type {
            case .BUY, .REWARD, .DEPOSIT:
                let received = t.receivedExact
                if received <= 0 {
                    // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
                    expense(e, .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                if let price {
                    // Gebühren in USDT und im dritten Coin gehören zum Einstand (Herkunft: Erwerb)
                    book.addCost(t.acquisitionTime, amount * price + t.quoteFeeExact + (feeValue ?? 0))
                    book.priced += received
                    if feeValue == nil { feeValueMissing = true }
                } else {
                    book.unpriced += received
                }
            case .SELL, .PAYMENT, .SWAP:
                // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                let swap = t.type == .SWAP
                let holdings = book.holdings
                // Abgegeben: Menge + Gebühr im Coin, gekappt auf den Bestand (mehr als vorhanden: markiert)
                let requested = amount + t.coinFeeExact
                if requested > holdings { oversold = true }
                // Erlös des ganzen Abgangs (Tausch: sein Wert); der gedeckte Teil bekommt seinen Anteil nach Menge
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { amount * $0 }
                let proceeds = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
                let feeMissing = gross != nil && feeValue == nil
                if feeMissing { feeValueMissing = true }
                if holdings <= 0 {
                    record(t, nil, hadPriced: false, proceeds: proceeds, coveredProceeds: nil, uncovered: requested,
                           feeLeg: false, feeMissing: feeMissing, swap: swap)
                    continue
                }
                let hadPriced = book.priced > 0
                let taken = book.take(requested)
                let sold = taken.removed == requested ? amount : PortfolioDecimal.divide(taken.removed * amount, requested)
                let covered = coveredShare(proceeds, removed: taken.removed, requested: requested)
                // Verkauf anteilig auf beide Teile; nur der Teil mit Kaufpreis ist bekannt
                if let covered, hadPriced {
                    realized += (taken.allPriced ? covered : PortfolioDecimal.multiply(covered, taken.share)) - taken.costRemoved
                    if !taken.allPriced { unknownSold += PortfolioDecimal.multiply(sold, 1 - taken.share) }
                    if feeValue == nil { realizedFeeMissing = true }
                } else {
                    unknownSold += sold
                }
                record(t, taken, hadPriced: hadPriced, proceeds: proceeds, coveredProceeds: covered,
                       uncovered: requested - taken.removed, feeLeg: false, feeMissing: feeMissing, swap: swap)
                soldTotal += sold
            case .WITHDRAWAL, .GIFT:
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                // Aus dem Portfolio genommen: zum Ø-Einstand, kein realisierter Gewinn
                let holdings = book.holdings
                let requested = amount + t.coinFeeExact
                if requested > holdings { oversold = true }
                if holdings <= 0 { continue }
                // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                expense(e, .WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings: holdings))
                let taken = book.take(requested)
                let sold = taken.removed == requested ? amount : PortfolioDecimal.divide(taken.removed * amount, requested)
                withdrawn += sold
                withdrawals.append(PortfolioWithdrawalCost(trade: t, sold: sold, removed: taken.removed,
                                                           pricedRemoved: taken.removedPriced, cost: taken.costRemoved))
            case .TRANSFER:
                let holdings = book.holdings
                if holdings <= 0 { continue }
                let allPriced = book.unpriced == 0
                // Gebühr USDT + Wert der Gebühr im dritten Coin: zum Einstand des Teils mit Kaufpreis; der Anteil des
                // Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: die ganze Gebühr) wird vermerkt
                let extra = t.quoteFeeExact + (feeValue ?? 0)
                var assigned: Decimal = 0
                if book.priced > 0 {
                    if feeValue == nil { feeValueMissing = true }
                    assigned = allPriced ? extra : PortfolioDecimal.divide(extra * book.priced, holdings)
                }
                if extra > 0 {
                    book.addCost(t.time, assigned)
                    if extra > assigned {
                        unassignedFees.append(PortfolioUnassignedFee(coin: symbol, txId: t.id, time: t.time, usdt: extra - assigned))
                    }
                }
                // Netzwerkgebühr im Coin: nur die Menge sinkt, der Einstand bleibt im Rest (Kosten des Haltens)
                let coinFee = t.coinFeeExact
                if coinFee > holdings { oversold = true }
                if coinFee > 0 {
                    let removed = min(coinFee, holdings)
                    let removedPriced = allPriced ? removed
                        : min(PortfolioDecimal.amount(PortfolioDecimal.divide(removed * book.priced, holdings)), book.priced)
                    book.priced -= removedPriced
                    if PortfolioDecimal.isDust(book.priced) || book.priced < 0 {
                        // Kein Teil mit Kaufpreis mehr: Einstand nicht still verlieren, sondern vermerken
                        if book.cost != 0 {
                            lostFeeCosts.append(PortfolioLostFeeCost(coin: symbol, txId: t.id, time: t.time, costUsdt: book.cost))
                        }
                        book.resetPriced()
                    }
                    book.unpriced = max(0, holdings - removed - book.priced)
                    if PortfolioDecimal.isDust(book.unpriced) { book.unpriced = 0 }
                }
            }
        }

        return PortfolioBookResult(
            priced: book.priced, unpriced: book.unpriced, cost: book.cost, realized: realized,
            unknownSold: unknownSold, soldTotal: soldTotal, withdrawn: withdrawn, oversold: oversold,
            feeValueMissing: feeValueMissing, realizedFeeMissing: realizedFeeMissing,
            lots: [], disposals: disposals, withdrawals: withdrawals, lostFeeCosts: lostFeeCosts,
            unassignedFees: unassignedFees)
    }

    /// Gesamtsicht nach `method`; `prices` = aktueller USDT-Kurs je Coin (Grossschreibung). Adress-Guthaben zählen
    /// im Gesamtwert, aber nicht in Einstand und «Seit Kauf» — ein Coin nur aus Adressen fehlt dort ohne
    /// «ohne Kaufpreis»-Hinweis (`missingCostCoins` nennt nur eigene Bestände ohne Kaufpreis). Wie Android.
    static func summarize(_ trades: [PortfolioTx], prices: [String: Double],
                          method: CostMethod = .defaultMethod,
                          withdrawalPrice: ((String, Int64) -> Double?)? = nil) -> PortfolioSummary {
        // Gebühr-Zeilen zum Tageskurs (gleicher Kurs wie der Abfluss im Verlauf); ohne Kurs Erlös unbekannt
        let trades = feeValued(trades, withdrawalPrice)
        // Auch Coins, die nur als Gebühr-Coin vorkommen (Bestand 0, ggf. «mehr als vorhanden»).
        // Schritte je Coin einmal gesammelt (statt je Coin über alle Transaktionen)
        let split = AddressBalance.split(trades)
        var byCoin: [String: [PortfolioLedgerEvent]] = [:]
        for entry in PortfolioLedger.eventsByCoin(split.real) { byCoin[entry.coin] = entry.events }
        let ledgers = PortfolioLedger.coins(trades).map {
            ledgerOf($0, events: byCoin[$0] ?? [], addressAmount: split.address[$0], currentPrice: prices[$0],
                     method: method)
        }
        let positions = ledgers.map(\.position)
        let withdrawn = withdrawnGain(ledgers.flatMap(\.withdrawals), marketPrice: withdrawalPrice)

        let open = positions.filter(\.isOpen).sorted { a, b in
            let va = a.value ?? -1, vb = b.value ?? -1
            return va != vb ? va > vb : a.coin < b.coin
        }
        // Ganz verkaufte Positionen bleiben sichtbar, auch wenn der Gewinn/Verlust 0 oder unbekannt ist
        let closed = positions.filter { !$0.isOpen && ($0.realizedExact != 0 || $0.soldAmount > 0 || $0.withdrawnAmount > 0) }
            .sorted { $0.coin < $1.coin }

        let totalValue = open.reduce(0.0) { $0 + ($1.value ?? 0) }
        let missingCurrent = open.filter { $0.value == nil }.map(\.coin)
        // Nur eigene Bestände ohne Kaufpreis; reine Adress-Guthaben haben keinen Einstand und fehlen nicht.
        // Ganz ohne Kaufpreis: fehlt in «Seit Kauf»; nur ein Teil ohne: zählt mit dem Teil mit Kaufpreis.
        let missingCost = open.filter { $0.pricedCostExact == nil && $0.ledgerHoldingsExact > 0 }.map(\.coin)
        let partialCost: [PortfolioUnpricedPart] = open
            .filter { $0.priceMissing && $0.pricedCostExact != nil && $0.unpricedHoldingsExact > 0 }
            .map { PortfolioUnpricedPart(coin: $0.coin, amount: $0.unpricedHoldingsExact) }
        let withCost = open.filter { $0.costBasis != nil }
        let investedExact: Decimal? = withCost.isEmpty ? nil : withCost.reduce(Decimal(0)) { $0 + ($1.costBasisExact ?? 0) }
        let invested = investedExact.map(PortfolioDecimal.double)
        // Gewinn seit Kauf über die Teile mit Kaufpreis der Coins mit Kurs; die übrigen nennt der Hinweis
        // («ohne 1 Coin (kein Kaufpreis)», «ohne 0.5 BTC ohne Kaufpreis», «Kein aktueller Kurs für …»).
        // Prozent auf den Einstand dieser Teile.
        let included = open.filter { $0.unrealizedExact != nil && $0.pricedCostExact != nil }
        // Exakt summiert (Einstand und Gewinn je Coin als Decimal), Double nur für die Anzeige
        let includedCost = included.reduce(Decimal(0)) { $0 + ($1.pricedCostExact ?? 0) }
        let unrealizedExact: Decimal? = included.isEmpty ? nil
            : included.reduce(Decimal(0)) { $0 + ($1.unrealizedExact ?? 0) }
        let unrealized = unrealizedExact.map(PortfolioDecimal.double)
        var unrealizedPercent: Double?
        if let unrealizedExact, includedCost > 0 {
            unrealizedPercent = PortfolioDecimal.double(PortfolioDecimal.divide(unrealizedExact, includedCost)) * 100
        }
        let realizedExact = positions.reduce(Decimal(0)) { $0 + $1.realizedExact }

        return PortfolioSummary(
            open: open,
            closed: closed,
            totalValue: totalValue,
            invested: invested,
            unrealized: unrealized,
            unrealizedPercent: unrealizedPercent,
            realized: PortfolioDecimal.double(realizedExact),
            costMissing: !missingCost.isEmpty || !partialCost.isEmpty,
            missingCurrentPrices: missingCurrent,
            realizedIncomplete: positions.contains { $0.realizedIncomplete },
            missingCostCoins: missingCost,
            investedExact: investedExact,
            realizedExact: realizedExact,
            feeValueMissingCoins: positions.filter(\.feeValueMissing).map(\.coin).sorted(),
            addressValue: open.reduce(0.0) { $0 + ($1.addressValueExact.map(PortfolioDecimal.double) ?? 0) },
            method: method,
            withdrawnGainExact: withdrawn.count > 0 && (withdrawn.amount != 0 || !withdrawn.incomplete) ? withdrawn.amount : nil,
            withdrawnGainIncomplete: withdrawn.incomplete,
            partialCostParts: partialCost,
            unassignedFeeUsdt: positions.reduce(Decimal(0)) { $0 + $1.unassignedFeeUsdt }
        )
    }
}
