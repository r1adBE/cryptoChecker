import Foundation

// Bausteine der Buchhaltung je Coin: Einstandsmethode, Wallets, Posten, Veräusserungen, Ergebnis eines Buchs —
// wie `PortfolioLots.kt` (Android).

/// Einstandsmethode des Portfolios (Einstellung «costMethod», Standard `.FIFO` für alle Installationen) — wie
/// `CostMethod` (Android). `.FIFO`: zuerst gekauft, zuerst verkauft — je Coin über ALLE Börsen/Wallets, nach Zeit,
/// dann Id. `.FIFO_WALLET`: FIFO je Börse/Wallet (Wallet = Börse der Transaktion, leer = Topf «ohne Angabe»; Umbuchungen
/// verschieben Posten mit Kaufdatum) — das BMF-Schreiben zu Kryptowerten (10.05.2022, neu 06.03.2025, Rn. 62:
/// walletbezogene Betrachtung) lässt das zu; das universelle FIFO bleibt Standard. `.AVERAGE`: gleitender Durchschnitt
/// («Ø»), wie bis Build 45. Rohwert wird so gespeichert; ältere Versionen kennen `.FIFO_WALLET` nicht (→ Standard).
enum CostMethod: String, Codable, CaseIterable, Sendable {
    case FIFO
    case FIFO_WALLET
    case AVERAGE

    static let defaultMethod: CostMethod = .FIFO

    /// Rechnet mit Posten (FIFO universell oder je Wallet).
    var isFifo: Bool { self != .AVERAGE }

    /// Gespeicherter Name; unbekannt oder leer = `defaultMethod`.
    static func fromName(_ name: String?) -> CostMethod {
        name.flatMap { CostMethod(rawValue: $0) } ?? defaultMethod
    }
}

/// Wallet-Zuordnung für `.FIFO_WALLET` — wie `PortfolioWallets` (Android): Schlüssel = Name getrimmt und klein
/// geschrieben; leer/fehlend = `unspecified` (Topf «ohne Angabe»). Angezeigt wird die zuerst gesehene Schreibweise.
enum PortfolioWallets {
    /// Topf «ohne Angabe» (Börse leer).
    static let unspecified = ""

    static func key(_ name: String?) -> String {
        guard let name else { return unspecified }
        return name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Wallet eines Schritts: Gebühr-Abgang in der Wallet der Haupttransaktion (bei einer Umbuchung die Quelle), sonst die Börse.
    static func of(_ e: PortfolioLedgerEvent) -> String? {
        e.trade.type == .TRANSFER ? e.trade.transferFrom : e.trade.exchange
    }
}

/// Offener Posten (FIFO): Rest eines Zugangs — wie `PortfolioLot` (Android). `costUsdt` nil = Kaufpreis unbekannt.
/// `acquiredTime` = Erwerb (`PortfolioTx.acquisitionTime`: bei einer Einzahlung das ursprüngliche Kaufdatum).
struct PortfolioLot: Sendable, Equatable {
    let coin: String
    /// Transaktion des Zugangs.
    let txId: Int64
    let acquiredTime: Int64
    /// `.BUY`, `.REWARD`, `.DEPOSIT` oder `.SWAP` (Zugang aus Tausch).
    let kind: PortfolioTxType
    let amount: Decimal
    let costUsdt: Decimal?
    /// `.FIFO_WALLET`: Wallet (Anzeigename, `PortfolioWallets.unspecified` = ohne Angabe); sonst nil.
    var wallet: String? = nil
}

/// Offene Position für die Anzeige «Offene Posten» (`PortfolioCalculator.openLots`) — wie `PortfolioOpenLot` (Android):
/// FIFO je Posten (Kaufdatum, Restmenge, Rest-Einstand, Wallet), Ø zusammengefasst (`aggregated`: Teil mit Kaufpreis
/// und Teil ohne, je nur wenn > 0). `costUsdt` nil = Kaufpreis unbekannt.
struct PortfolioOpenLot: Sendable, Equatable {
    let coin: String
    let txId: Int64?
    let acquiredTime: Int64?
    let kind: PortfolioTxType?
    let amount: Decimal
    let costUsdt: Decimal?
    let wallet: String?
    let aggregated: Bool
}

/// `.FIFO_WALLET`: Abgang grösser als der Bestand DIESER Wallet (der fehlende Teil `missing` wird nicht aus anderen Wallets
/// genommen) — wie `PortfolioWalletShortfall` (Android). Anzeige «Bestand in … reicht nicht – fehlt eine Umbuchung?».
struct PortfolioWalletShortfall: Sendable, Equatable {
    let coin: String
    /// Anzeigename; `PortfolioWallets.unspecified` = ohne Angabe.
    let wallet: String
    let txId: Int64
    let time: Int64
    let type: PortfolioTxType
    let feeLeg: Bool
    let missing: Decimal
}

/// Teil eines Einstands: Betrag (USDT) aus einem Zugang bzw. einer Gebühr zum Zeitpunkt `time` (Devisenkurs) — FIFO je
/// Posten (Erwerb, zugeschlagene Umbuchungsgebühren, übernommener Netzwerkgebühr-Einstand), Ø je Topf. Wie
/// `PortfolioCostPart` (Android).
struct PortfolioCostPart: Sendable, Equatable {
    let time: Int64
    let usdt: Decimal
}

/// Herkunftsteile eines Einstands (`PortfolioCostPart`) — gemeinsame Rechnung der FIFO-Posten (`PortfolioFifo`); wie
/// `PortfolioCostParts` (Android). Invariante: Summe der Teile = Einstand (exakt).
enum PortfolioCostParts {
    /// `usdt` zum Zeitpunkt `time` in `parts`; gleicher Zeitpunkt wird addiert (gleicher Devisenkurs), 0 = nichts.
    static func add(_ parts: inout [PortfolioCostPart], _ time: Int64, _ usdt: Decimal) {
        if usdt == 0 { return }
        if let i = parts.firstIndex(where: { $0.time == time }) {
            parts[i] = PortfolioCostPart(time: time, usdt: parts[i].usdt + usdt)
        } else {
            parts.append(PortfolioCostPart(time: time, usdt: usdt))
        }
    }

    /// Nimmt `target` von `parts` (Summe `total`) anteilig aus allen Teilen heraus (die Teile sinken entsprechend);
    /// Rückgabe = die genommenen Teile, Summe exakt `target` (Rest beim letzten Teil).
    static func take(_ parts: inout [PortfolioCostPart], total: Decimal, target: Decimal) -> [PortfolioCostPart] {
        if target == 0 || parts.isEmpty { return [] }
        if target >= total {
            let all = parts
            parts.removeAll()
            return all
        }
        var out: [PortfolioCostPart] = []
        out.reserveCapacity(parts.count)
        var left = target
        for i in parts.indices {
            let p = parts[i]
            let r = i == parts.count - 1 ? left : PortfolioDecimal.divide(p.usdt * target, total)
            left -= r
            out.append(PortfolioCostPart(time: p.time, usdt: r))
            parts[i] = PortfolioCostPart(time: p.time, usdt: p.usdt - r)
        }
        return out
    }
}

/// Eine Veräusserung (Steuer-Export): Verkauf oder Gebühr in einem dritten Coin (`feeLeg`) — wie
/// `PortfolioDisposal` (Android). FIFO: ein Eintrag je verbrauchtem Posten; Ø: ein Eintrag je Verkauf (bei Bestand
/// teils ohne Kaufpreis zwei), ohne Posten-Angaben (nil). Mehr verkauft als erfasst: der ungedeckte Rest als eigener
/// Eintrag (`uncovered`) mit Erlös, ohne Kaufpreis. Beträge in USDT, exakt; nil = unbekannt.
struct PortfolioDisposal: Sendable, Equatable {
    let coin: String
    /// Verkauf bzw. (bei `feeLeg`) die Transaktion, deren Gebühr in diesem Coin bezahlt wurde.
    let sellTxId: Int64
    let sellTime: Int64
    /// FIFO: Zugang des Postens; Ø und `uncovered`: nil.
    let lotTxId: Int64?
    let acquiredTime: Int64?
    let lotKind: PortfolioTxType?
    /// Abgegebene Menge dieses Teils (inkl. anteiliger Gebühr im Coin).
    let amount: Decimal
    /// Einstand dieses Teils; nil = Kaufpreis unbekannt.
    let costUsdt: Decimal?
    /// Erlös dieses Teils (anteilig: Menge × Preis − Gebühr USDT − Wert der Gebühr im dritten Coin); nil = ohne Preis.
    let proceedsUsdt: Decimal?
    /// Erlös − Einstand; nil, wenn eins davon unbekannt ist.
    let gainUsdt: Decimal?
    /// UTC-Kalendertage zwischen Zugang und Veräusserung (Tag(Verkauf) − Tag(Zugang)); Ø: nil.
    let holdingDays: Int?
    /// Veräusserung des Gebühr-Coins (Gebühr einer anderen Transaktion in diesem Coin bezahlt).
    let feeLeg: Bool
    /// Erlös ohne die Gebühr im dritten Coin gerechnet (deren Preis fehlt).
    let feeValueMissing: Bool
    let method: CostMethod
    /// Herkunft von `costUsdt` (Summe = `costUsdt`) für die Umrechnung je Teil zum Kurs seines Datums: FIFO der Posten
    /// (Erwerb plus zugeschlagene Gebühren je zu ihrem Zeitpunkt), Ø anteilig aus dem Topf; leer ohne Kaufpreis bzw. bei
    /// Einstand 0 und bei Gebühr-Aufwänden.
    var costParts: [PortfolioCostPart] = []
    /// Teil über den erfassten Bestand hinaus (mehr verkauft als erfasst): Kaufpreis fehlt.
    var uncovered = false
    /// `.FIFO_WALLET`: Wallet des Abgangs (`PortfolioWallets.unspecified` = ohne Angabe); sonst nil.
    var wallet: String? = nil
    /// Veräusserung durch einen Tausch (`.SWAP`).
    var swap = false
    /// Schenkung als Veräusserung zum Marktwert (`.GIFT` bei UK/CA/AU, `PortfolioTx.fromGift`): Vermerk «Schenkung».
    var gift = false
    /// Zahlung mit Krypto (`.PAYMENT`, Veräusserung zum Marktwert): Vermerk «Zahlung mit Krypto».
    var payment = false
    /// Erlös aus geschätztem Wert (`PortfolioTx.valueEstimated`, Tagesschluss): Vermerk «Wert geschätzt (Tagesschluss)».
    var valueEstimated = false
    // ---- Nur Steuer-Vorlagen (`TaxEngines`), wie Android ----
    /// `.UK_POOL`: Zuordnung (gleicher Tag, 30 Tage, Pool); sonst nil.
    var match: TaxMatch? = nil
    /// Same-Day/30 Tage: Datum des zugeordneten Zugangs (Anzeige «Kaufdatum», keine Haltedauer); sonst nil.
    var matchedTime: Int64? = nil
    /// `.CA_ACB`: nicht abziehbarer Verlust (Superficial Loss, ≥ 0), dem ACB zugeschlagen; sonst nil.
    var adjustmentUsdt: Decimal? = nil
    /// `.AT_MOVING_AVERAGE`: true = Altbestand (vor dem 1.3.2021), false = Neubestand; sonst nil.
    var oldHolding: Bool? = nil
    /// `.FR_PTA`: Erwerbspreis-Summe unvollständig.
    var costIncomplete = false
    /// `.FR_PTA`: Gesamtwert des Portfolios unmittelbar vor der Abtretung (USDT); sonst nil.
    var portfolioValueUsdt: Decimal? = nil
    /// `.FR_PTA`: Abtretungspreis vor Gebühren; sonst nil.
    var grossProceedsUsdt: Decimal? = nil
    /// `.FR_PTA`: `portfolioValueUsdt` enthält Guthaben beobachteter Adressen — ohne Einstand in der Erwerbspreis-Summe,
    /// der Gewinn ist dadurch eher zu hoch (`TaxSummaryNote.FR_ADDRESS_NO_COST`). Wie Android.
    var addressValueIncluded = false
    /// Gebühr als Aufwand (`PortfolioFeeExpenses`): Einstand = Aufwand, Erlös 0; sonst nil.
    var feeExpense: PortfolioFeeExpense? = nil
    /// UK/CA: Schenkung an Ehe-/Lebenspartner (`PortfolioTx.isGiftToSpouse`) — Erlös = Einstand, kein Gewinn/Verlust (TCGA 1992 s. 58,
    /// ITA 73(1)); der Einstand geht auf den Partner über. Vermerk im Export. Wie Android.
    var spouseGift = false
    /// `.UK_POOL`: Erlös vor Gebühren (Menge × Preis bzw. Wert des Tauschs, wie `proceedsUsdt` nach Menge verteilt) — die
    /// Meldegrenze an HMRC gilt für die Erlöse vor Abzug der Kosten (`TaxTemplate.ukReportProceedsGbp`); sonst nil. Wie Android.
    var proceedsBeforeFeesUsdt: Decimal? = nil
}

/// Zuordnung einer Veräusserung nach HMRC (`.UK_POOL`, CRYPTO22200) — wie `TaxMatch` (Android).
enum TaxMatch: String, Sendable, Equatable {
    case SAME_DAY
    case THIRTY_DAY
    case POOL
}

/// Eine Auszahlung im Buch: abgegebene Menge und Einstand der verbrauchten Posten (FIFO) bzw. zum Ø — für die
/// Wertänderung bis zur Auszahlung (`PortfolioCalculator.withdrawnGain`). Wie `PortfolioWithdrawalCost` (Android).
struct PortfolioWithdrawalCost: Sendable, Equatable {
    let trade: PortfolioTx
    /// Ausgezahlte Menge ohne Gebühr im Coin (gekappt auf den Bestand).
    let sold: Decimal
    /// Abgegebene Menge samt Gebühr im Coin (gekappt).
    let removed: Decimal
    /// Davon mit Kaufpreis.
    let pricedRemoved: Decimal
    /// Einstand des Teils mit Kaufpreis.
    let cost: Decimal
}

/// Einstand einer Netzwerkgebühr im Coin (Umbuchung), der keinen bezahlten Posten mehr fand — wie
/// `PortfolioLostFeeCost` (Android): nicht still verloren, kein realisierter Gewinn/Verlust.
struct PortfolioLostFeeCost: Sendable, Equatable {
    let coin: String
    let txId: Int64
    let time: Int64
    let costUsdt: Decimal
}

/// «Offener Posten sichtbar» (Entscheid des Inhabers 17.10.2026): Anteil einer Umbuchungsgebühr (Gebühr USDT + Wert der
/// Gebühr im dritten Coin), der nach Menge auf Bestand OHNE Kaufpreis fällt — gehört zu dessen unbekanntem Einstand und
/// steht hier statt still zu entfallen (Anzeige «Gebühr nicht zugeordnet – Einstand unbekannt», Steuer-Export: Info-Zeile).
/// Weder im Einstand noch realisiert. Wie `PortfolioUnassignedFee` (Android).
struct PortfolioUnassignedFee: Sendable, Equatable {
    let coin: String
    let txId: Int64
    let time: Int64
    let usdt: Decimal
}

/// Kennzahlen eines Coins samt offener Posten (nur FIFO, sonst leer) und Veräusserungen.
struct CoinLedger: Sendable, Equatable {
    let position: CoinPosition
    let lots: [PortfolioLot]
    let disposals: [PortfolioDisposal]
    /// Auszahlungen mit ihrem Einstand.
    var withdrawals: [PortfolioWithdrawalCost] = []
    /// Offene Posten für die Anzeige (alle Methoden).
    var openLots: [PortfolioOpenLot] = []
    /// `.FIFO_WALLET`: Abgänge über den Bestand ihrer Wallet.
    var walletShortfalls: [PortfolioWalletShortfall] = []
    /// Einstand von Netzwerkgebühren ohne bezahlten Posten.
    var lostFeeCosts: [PortfolioLostFeeCost] = []
    /// Umbuchungsgebühren auf Bestand ohne Kaufpreis (`PortfolioUnassignedFee`), zeitlich.
    var unassignedFees: [PortfolioUnassignedFee] = []
}

/// Zwischenergebnis einer Methode (Ledger-Teil, ohne Adress-Guthaben), Basis von `CoinPosition`.
struct PortfolioBookResult {
    var priced: Decimal = 0
    var unpriced: Decimal = 0
    var cost: Decimal = 0
    var realized: Decimal = 0
    var unknownSold: Decimal = 0
    var soldTotal: Decimal = 0
    var withdrawn: Decimal = 0
    var oversold = false
    var feeValueMissing = false
    var realizedFeeMissing = false
    var lots: [PortfolioLot] = []
    var disposals: [PortfolioDisposal] = []
    var withdrawals: [PortfolioWithdrawalCost] = []
    var shortfalls: [PortfolioWalletShortfall] = []
    var lostFeeCosts: [PortfolioLostFeeCost] = []
    var unassignedFees: [PortfolioUnassignedFee] = []
    /// Herkunft des Einstands `cost` des offenen Bestands mit Kaufpreis (`PortfolioCostPart`, Summe = `cost`): FIFO die
    /// Teile aller Posten mit Kaufpreis, Ø die Teile des Topfs nach den anteiligen Abgängen — für «Seit Kauf» in der
    /// Anzeigewährung zum Kurs des Erwerbstags (`PortfolioCostFx`). Wie Android.
    var openParts: [PortfolioCostPart] = []
}
