import Foundation

// Ergebnisse von `PortfolioCalculator`: Kennzahlen je Coin und Summe des Portfolios — wie `CoinPosition` und
// `PortfolioSummary` in `PortfolioModels.kt` (Android).

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
    /// Herkunft von `pricedCostExact` (`PortfolioCostPart`: Zeitpunkt und Betrag je Zugang bzw. zugeschlagener Gebühr,
    /// Summe = `pricedCostExact`) nach der Einstandsmethode — FIFO die offenen Posten, Ø der Topf nach anteiligen
    /// Abgängen. Leer ohne Teil mit Kaufpreis bzw. bei Einstand 0. Basis von «Seit Kauf» in der Anzeigewährung
    /// (`PortfolioCostFx`). Wie Android.
    var pricedCostParts: [PortfolioCostPart] = []

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
