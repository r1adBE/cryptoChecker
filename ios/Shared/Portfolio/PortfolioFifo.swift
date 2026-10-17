import Foundation

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
    /// Gebühr als Aufwand (`PortfolioFeeExpenses`): Einstand = Aufwand, Erlös 0; sonst nil.
    var feeExpense: PortfolioFeeExpense? = nil
    /// UK/CA: Schenkung an Ehe-/Lebenspartner (`PortfolioTx.isGiftToSpouse`) — Erlös = Einstand, kein Gewinn/Verlust (TCGA 1992 s. 58,
    /// ITA 73(1)); der Einstand geht auf den Partner über. Vermerk im Export. Wie Android.
    var spouseGift = false
}

/// Art eines Gebühr-Aufwands (`PortfolioFeeExpenses`) — wie Android.
enum PortfolioFeeExpense: String, Sendable, Equatable {
    /// Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs).
    case CONSUMED
    /// Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung.
    case WITHDRAWAL
    /// Nur Steuer-Vorlagen mit `TaxEventRules.quoteFeeIsInfo` (Build 57): Gebühr USDT einer Umbuchung zwischen eigenen Wallets
    /// — nicht im Einstand, Zeile nur zur Information (`TaxEngines.disposals`). Das Portfolio rechnet sie weiter zum Einstand.
    case TRANSFER
}

/// Gebühr als Aufwand — für alle Methoden gleich, Regeln exakt wie `PortfolioFeeExpenses` in `PortfolioFifo.kt`:
///  - `.CONSUMED`: Gebühr im Coin ≥ Menge (Kauf/Belohnung/Einzahlung mit Preis): Aufwand = Menge × Preis + Gebühr USDT +
///    Wert der Gebühr im dritten Coin. Tausch mit Gebühr im erhaltenen Coin ≥ erhaltener Menge: Aufwand = Wert des Tauschs
///    (der abgegebene Coin bucht ihn als Erlös, der Aufwand hebt ihn auf — kein Schein-Gewinn). Ohne Preis/Wert: keiner.
///  - `.WITHDRAWAL`: Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung (nur mit Bestand; reicht er nicht,
///    zum gedeckten Anteil `PortfolioLedger.withdrawnShare` wie der Abfluss im Verlauf).
/// Je Fall eine Veräusserung: Einstand = Aufwand, Erlös 0, Gewinn = −Aufwand, Haltedauer 0.
enum PortfolioFeeExpenses {

    /// Aufwand (USDT) und «Wert der Gebühr im dritten Coin fehlt».
    struct Expense {
        let usdt: Decimal
        let feeValueMissing: Bool
    }

    /// Gebühr verbraucht den ganzen Zugang `e`; nil = nicht so oder ohne Preis/Wert.
    static func consumed(_ e: PortfolioLedgerEvent) -> Expense? {
        let t = e.trade
        if e.feeLeg || t.amountExact <= 0 { return nil }
        if e.swapIn {
            if t.swapToCoin == nil || t.swapReceived > 0 { return nil }
            return t.swapValue.map { Expense(usdt: $0, feeValueMissing: false) }
        }
        if !t.type.isInflow || t.receivedExact > 0 { return nil }
        guard let price = t.validPriceExact else { return nil }
        let feeValue = t.thirdFeeValue
        let usdt: Decimal = t.amountExact * price + t.quoteFeeExact + (feeValue ?? 0)
        return Expense(usdt: usdt, feeValueMissing: feeValue == nil)
    }

    /// Auszahlung bei Bestand `holdings`: Gebühr USDT + Wert der Gebühr im dritten Coin zum gedeckten Anteil
    /// (`PortfolioLedger.withdrawnShare`); nil = keine Gebühr oder nichts abgegeben.
    static func withdrawal(_ t: PortfolioTx, holdings: Decimal) -> Expense? {
        // Auszahlung und Verschenkt (wie eine Auszahlung)
        if !t.type.isWithdrawalLike || t.amountExact <= 0 { return nil }
        guard let share = PortfolioLedger.withdrawnShare(t, holdings: holdings) else { return nil }
        let feeValue = t.thirdFeeValue
        let full: Decimal = t.quoteFeeExact + (feeValue ?? 0)
        if full <= 0 && feeValue != nil { return nil }
        let usdt = share == 1 ? full : PortfolioDecimal.multiply(full, share)
        return Expense(usdt: usdt, feeValueMissing: feeValue == nil)
    }

    /// Menge der Zeile: verbrauchter Zugang (Tausch: erhaltene Menge), Auszahlung 0 (Gebühr in USDT).
    private static func amountOf(_ e: PortfolioLedgerEvent, _ kind: PortfolioFeeExpense) -> Decimal {
        if kind == .WITHDRAWAL || kind == .TRANSFER { return 0 }
        if e.swapIn { return e.trade.toAmountExact ?? 0 }
        return e.trade.amountExact
    }

    static func disposal(_ symbol: String, _ e: PortfolioLedgerEvent, _ kind: PortfolioFeeExpense, _ x: Expense,
                         method: CostMethod, wallet: String? = nil) -> PortfolioDisposal {
        let t = e.trade
        return PortfolioDisposal(
            coin: symbol, sellTxId: t.id, sellTime: t.time,
            lotTxId: t.id, acquiredTime: t.time, lotKind: e.swapIn ? .SWAP : t.type,
            amount: amountOf(e, kind), costUsdt: x.usdt, proceedsUsdt: 0, gainUsdt: -x.usdt,
            holdingDays: 0, feeLeg: false, feeValueMissing: x.feeValueMissing, method: method,
            wallet: wallet, feeExpense: kind)
    }

    /// Alle Gebühr-Aufwände eines Coins (eigene Rechnungen der Steuer-Vorlagen, `TaxEngines`); Auszahlung nur mit Bestand
    /// (`PortfolioHoldingsBook`, ein Topf). `swaps` false: ohne den verbrauchten Zugang eines Tauschs.
    static func of(_ symbol: String, _ events: [PortfolioLedgerEvent], swaps: Bool = true) -> [PortfolioDisposal] {
        var out: [PortfolioDisposal] = []
        var book = PortfolioHoldingsBook(method: .FIFO)
        for e in events {
            if let x = consumed(e), swaps || !e.swapIn {
                out.append(disposal(symbol, e, .CONSUMED, x, method: .FIFO))
            }
            if !e.feeLeg && !e.swapIn && book.cap(for: e) > 0, let x = withdrawal(e.trade, holdings: book.cap(for: e)) {
                out.append(disposal(symbol, e, .WITHDRAWAL, x, method: .FIFO))
            }
            book.apply(e)
        }
        return out
    }
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
}

/// FIFO-Buchhaltung eines Coins — Regeln exakt wie `PortfolioFifo.kt` (dort ausführlich beschrieben):
///  - Kauf, Belohnung, Einzahlung: neuer Posten (ohne Preis mit unbekanntem Einstand).
///  - Reihenfolge der Posten: nach Erwerb (`PortfolioTx.acquisitionTime`, bei einer Einzahlung mit ursprünglichem
///    Kaufdatum dieses), dann Zeit der Transaktion, dann Id; Abgänge verbrauchen immer die ältesten ERWORBENEN Posten.
///    Haltedauer ab dem Erwerb.
///  - Verkauf: verbraucht Posten, älteste zuerst — zuerst die verkaufte Menge, danach die Gebühr im Coin aus den
///    nächsten Posten; Erlös nur nach VERKAUFTER Menge verteilt (Gebühr-Coins: Erlös 0, ihr Einstand = Kosten der
///    Veräusserung in ihrem Posten), Gewinn je Teil = Erlösanteil − Einstand; Teile ohne Kaufpreis (oder Verkauf ohne Preis) unbekannt. Mehr
///    verkauft als erfasst: Bestand gekappt und markiert; der ungedeckte Rest wird eine Veräusserung ohne Kaufpreis
///    (`uncovered`), damit im Steuer-Export kein Verkauf fehlt.
///  - Auszahlung: verbraucht Posten, älteste zuerst, ohne Gewinn und ohne Veräusserung (Einstand in `withdrawals`);
///    Gebühr USDT/dritter Coin: Aufwand (`PortfolioFeeExpenses`).
///  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs): kein
///    Posten, Aufwand realisiert (`PortfolioFeeExpenses`).
///  - Umbuchung: Netzwerkgebühr im Coin verbraucht Menge (älteste zuerst), ihr bekannter Einstand geht auf den
///    ältesten verbleibenden Posten mit Kaufpreis über (Kosten des Haltens/Verschiebens, keine Veräusserung — wird mit
///    dem nächsten Verkauf realisiert); ohne solchen Posten wartet er auf den nächsten später hinzukommenden Posten mit
///    Kaufpreis, erst ohne einen solchen bis zum Ende `PortfolioLostFeeCost`; Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf alle Posten
///    (Anteil ohne Kaufpreis gehört zu deren unbekanntem Einstand und wird als `PortfolioUnassignedFee` vermerkt).
///  - Herkunft des Einstands: jeder Posten führt seinen Einstand in Teilen mit Zeitpunkt (Erwerb, Umbuchungsgebühr zum
///    Zeitpunkt der Umbuchung, übernommener Netzwerkgebühr-Einstand mit seinen Teilen); ein Abgang nimmt sie anteilig mit
///    (`PortfolioDisposal.costParts`), der Steuer-Export rechnet jeden Teil zum Kurs SEINES Datums um. Anfangsposten des
///    US-Übergangs tragen nur ihr Erwerbsdatum (Steuerwährung USD, Kurs 1).
///  - Gebühr im dritten Coin: Veräusserung des Gebühr-Coins (FIFO) zum Gebühr-Preis (`feeLeg`).
///  - Tausch: im abgegebenen Coin wie ein Verkauf (Erlös = Wert − Gebühr USDT − Wert der Gebühr im dritten Coin), im
///    erhaltenen Coin ein Posten `.SWAP` (Einstand = Wert, Erwerb = Zeit des Tauschs, Menge − Gebühr im erhaltenen Coin).
///  - Je Wallet (`perWallet`): je Wallet eine Warteschlange; Umbuchung A → B verschiebt (nach der Netzwerkgebühr) die
///    ältesten Posten von A mit Kaufdatum nach B; fehlt Bestand in einer Wallet, wird nicht aus anderen genommen
///    (ungedeckter Rest bzw. kommt nicht an, `PortfolioWalletShortfall`).
///  - Bestand läuft als Summe mit (kein erneutes Aufsummieren je Schritt).
enum PortfolioFifo {
    private static let dayMillis: Int64 = 86_400_000

    private final class Lot {
        let txId: Int64
        /// Erwerb (Reihenfolge, Haltedauer).
        let acquired: Int64
        /// Zeit der Transaktion (bei gleichem Erwerb).
        let time: Int64
        let kind: PortfolioTxType
        var amount: Decimal
        /// Rest-Einstand; nil = Kaufpreis unbekannt. Ändert sich nur zusammen mit `parts`.
        private(set) var cost: Decimal?
        /// Herkunft von `cost` (Summe = `cost`); leer ohne Kaufpreis bzw. bei Einstand 0.
        private(set) var parts: [PortfolioCostPart] = []

        /// `parts` nil = ganzer Einstand zum Erwerb.
        init(txId: Int64, acquired: Int64, time: Int64, kind: PortfolioTxType, amount: Decimal, cost: Decimal?,
             parts: [PortfolioCostPart]? = nil) {
            self.txId = txId
            self.acquired = acquired
            self.time = time
            self.kind = kind
            self.amount = amount
            self.cost = cost
            if let cost {
                if let parts {
                    for p in parts { PortfolioCostParts.add(&self.parts, p.time, p.usdt) }
                } else {
                    PortfolioCostParts.add(&self.parts, acquired, cost)
                }
            }
        }

        /// Gebühr bzw. übernommener Einstand `usdt` zum Zeitpunkt `at` (nur mit Kaufpreis).
        func addCost(_ at: Int64, _ usdt: Decimal) {
            guard let c = cost else { return }
            cost = c + usdt
            PortfolioCostParts.add(&parts, at, usdt)
        }

        /// Übernimmt Einstand samt Herkunft `from` (nur mit Kaufpreis).
        func addParts(_ from: [PortfolioCostPart]) {
            for p in from { addCost(p.time, p.usdt) }
        }

        /// Einstand der Teilmenge `qty` (ganze Menge: ganzer Einstand) samt Herkunft herausnehmen; nil ohne Kaufpreis.
        func takeCost(_ qty: Decimal) -> (cost: Decimal, parts: [PortfolioCostPart])? {
            guard let c = cost else { return nil }
            let removed = qty == amount ? c : PortfolioDecimal.divide(c * qty, amount)
            let taken = PortfolioCostParts.take(&parts, total: c, target: removed)
            cost = c - removed
            return (removed, taken)
        }

        /// Liegt vor `other`: Erwerb, dann Zeit, dann Id.
        func isBefore(_ other: Lot) -> Bool {
            if acquired != other.acquired { return acquired < other.acquired }
            if time != other.time { return time < other.time }
            return txId < other.txId
        }
    }

    /// Warteschlange einer Wallet (universell: eine einzige).
    private final class Queue {
        let key: String
        var lots: [Lot] = []
        var held: Decimal = 0 // Σ Menge der Posten
        /// Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet auf den nächsten Posten mit Kaufpreis.
        var pendingLost: [PortfolioLostFeeCost] = []
        /// Herkunft von `pendingLost` (Summe gleich).
        var pendingParts: [PortfolioCostPart] = []

        init(key: String) { self.key = key }
    }

    /// Verbrauchter Teil eines Postens: Menge, Einstand (nil = ohne Kaufpreis) und dessen Herkunft.
    private struct Slice {
        let txId: Int64
        let acquired: Int64
        let time: Int64
        let kind: PortfolioTxType
        let amount: Decimal
        let cost: Decimal?
        let parts: [PortfolioCostPart]
    }

    /// Summe der Einstände (ohne Kaufpreis = 0).
    private static func costSum(_ slices: [Slice]) -> Decimal { slices.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) } }

    /// UTC-Kalendertage zwischen `from` und `to`.
    static func holdingDays(from: Int64, to: Int64) -> Int {
        PortfolioHistory.epochDay(utcMillis: to) - PortfolioHistory.epochDay(utcMillis: from)
    }

    /// `seed`: Anfangsposten je Wallet (`PortfolioLot.wallet` = Anzeigename) vor den Ereignissen — US-Übergang
    /// (`TaxEngines.usTransition`); sonst leer. Wie Android.
    static func run(_ symbol: String, events: [PortfolioLedgerEvent], perWallet: Bool = false,
                    seed: [PortfolioLot] = []) -> PortfolioBookResult {
        let method: CostMethod = perWallet ? .FIFO_WALLET : .FIFO
        var queues: [Queue] = []
        var queueIndex: [String: Int] = [:]
        var names: [String: String] = [:]
        var r = PortfolioBookResult()

        /// Warteschlange der Wallet `raw` (universell immer dieselbe).
        func queue(_ raw: String?) -> Queue {
            let key = perWallet ? PortfolioWallets.key(raw) : PortfolioWallets.unspecified
            if perWallet && names[key] == nil {
                names[key] = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            }
            if let i = queueIndex[key] { return queues[i] }
            let q = Queue(key: key)
            queueIndex[key] = queues.count
            queues.append(q)
            return q
        }

        /// Anzeigename der Wallet von `q` (nur je Wallet, sonst nil).
        func walletOf(_ q: Queue) -> String? { perWallet ? (names[q.key] ?? PortfolioWallets.unspecified) : nil }

        /// Abgang `requested` grösser als der Bestand von `q`: markiert, je Wallet mit Fehlmenge.
        func check(_ q: Queue, _ requested: Decimal, _ t: PortfolioTx, feeLeg: Bool) {
            guard requested > q.held else { return }
            r.oversold = true
            if perWallet {
                r.shortfalls.append(PortfolioWalletShortfall(
                    coin: symbol, wallet: walletOf(q) ?? PortfolioWallets.unspecified, txId: t.id, time: t.time,
                    type: t.type, feeLeg: feeLeg, missing: requested - max(0, q.held)))
            }
        }

        /// Neuer Posten an seinem Platz (meist hinten); ein Posten mit Kaufpreis übernimmt wartenden Gebühr-Einstand.
        func insert(_ q: Queue, _ lot: Lot) {
            if lot.cost != nil, !q.pendingLost.isEmpty {
                lot.addParts(q.pendingParts)
                q.pendingLost.removeAll()
                q.pendingParts.removeAll()
            }
            var i = q.lots.count
            while i > 0 && lot.isBefore(q.lots[i - 1]) { i -= 1 }
            q.lots.insert(lot, at: i)
            q.held += lot.amount
        }

        /// Verbraucht `requested` (gekappt auf den Bestand von `q`), älteste Posten zuerst.
        func consume(_ q: Queue, _ requested: Decimal) -> [Slice] {
            var out: [Slice] = []
            var left = min(requested, q.held)
            while left > 0, let lot = q.lots.first {
                let qty = min(left, lot.amount)
                // Einstand anteilig samt Herkunft (vor dem Kürzen der Menge)
                let taken = lot.takeCost(qty)
                lot.amount -= qty
                q.held -= qty
                if lot.amount <= 0 { q.lots.removeFirst() }
                left -= qty
                out.append(Slice(txId: lot.txId, acquired: lot.acquired, time: lot.time, kind: lot.kind, amount: qty,
                                 cost: taken?.cost, parts: taken?.parts ?? []))
            }
            if q.lots.isEmpty { q.held = 0 }
            return out
        }

        /// Gebühr USDT + Wert der Gebühr im dritten Coin (`extra`) einer Umbuchung zum Zeitpunkt `at` nach Menge auf `lots`
        /// (Summe `h`); der letzte Posten bekommt den Rest, wenn alle einen Kaufpreis haben. Rückgabe = Anteil der Posten
        /// OHNE Kaufpreis (`unassign`); 0 = alles zugeordnet.
        func spread(_ lots: [Lot], _ extra: Decimal, _ h: Decimal, at: Int64) -> Decimal {
            guard extra > 0, h > 0 else { return 0 }
            let allPriced = lots.allSatisfy { $0.cost != nil }
            var left = extra
            var assigned: Decimal = 0
            for (i, lot) in lots.enumerated() {
                let part = allPriced && i == lots.count - 1 ? left : PortfolioDecimal.divide(extra * lot.amount, h)
                left -= part
                if lot.cost != nil {
                    lot.addCost(at, part)
                    assigned += part
                }
            }
            return extra - assigned
        }

        /// Einstand der Netzwerkgebühr ohne bezahlten Posten in `q` (`carried`): nicht still verlieren — wartet auf den
        /// nächsten Posten mit Kaufpreis (`insert`), sonst am Ende vermerkt.
        func lose(_ q: Queue, _ t: PortfolioTx, _ carried: [Slice]) {
            let cost = costSum(carried)
            if cost == 0 { return }
            q.pendingLost.append(PortfolioLostFeeCost(coin: symbol, txId: t.id, time: t.time, costUsdt: cost))
            for s in carried { for p in s.parts { PortfolioCostParts.add(&q.pendingParts, p.time, p.usdt) } }
        }

        /// Netzwerkgebühr-Einstand `carried` auf `target` (Posten mit Kaufpreis), sonst wartend in `q` (`lose`).
        func carry(_ target: Lot?, _ q: Queue, _ t: PortfolioTx, _ carried: [Slice]) {
            if costSum(carried) == 0 { return }
            if let target {
                for s in carried { target.addParts(s.parts) }
            } else {
                lose(q, t, carried)
            }
        }

        /// Umbuchungsgebühr-Anteil `usdt` auf Bestand ohne Kaufpreis: vermerkt (`PortfolioUnassignedFee`).
        func unassign(_ t: PortfolioTx, _ usdt: Decimal) {
            if usdt > 0 { r.unassignedFees.append(PortfolioUnassignedFee(coin: symbol, txId: t.id, time: t.time, usdt: usdt)) }
        }

        /// Gebühr als Aufwand (`PortfolioFeeExpenses`): realisiert −Aufwand, eine Veräusserung mit Erlös 0.
        func expense(_ e: PortfolioLedgerEvent, _ q: Queue, _ kind: PortfolioFeeExpense, _ x: PortfolioFeeExpenses.Expense?) {
            guard let x else { return }
            r.realized -= x.usdt
            if x.feeValueMissing { r.feeValueMissing = true }
            r.disposals.append(PortfolioFeeExpenses.disposal(symbol, e, kind, x, method: method, wallet: walletOf(q)))
        }

        /// Veräusserung: Erlös `proceeds` (nil = unbekannt) des ganzen Abgangs auf die Teile und den ungedeckten Rest
        /// `uncovered` verteilt — nach Menge, mit `soldParts` nur nach VERKAUFTER Menge je Teil (Gebühr-Coins: Erlös 0;
        /// `uncoveredSold` = verkaufte Menge im ungedeckten Rest). `sold` = verkaufte Menge ohne Gebühr im Coin, gedeckter Teil.
        func dispose(_ t: PortfolioTx, _ q: Queue, _ slices: [Slice], uncovered: Decimal, proceeds: Decimal?, sold: Decimal,
                     feeLeg: Bool, feeMissing: Bool, swap: Bool = false, soldParts: [Decimal]? = nil,
                     uncoveredSold: Decimal? = nil) {
            let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
            let total = removed + uncovered
            guard total > 0 else { return }
            let wallet = walletOf(q)
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            let gift = !feeLeg && t.fromGift
            let payment = !feeLeg && t.type == .PAYMENT && !t.fromGift
            let estimated = !feeLeg && t.valueEstimated
            let weights = soldParts ?? slices.map(\.amount)
            let restWeight = soldParts != nil ? (uncoveredSold ?? uncovered) : uncovered
            let weightTotal = weights.reduce(Decimal(0), +) + restWeight
            var proceedsLeft = proceeds
            // Letzter Teil MIT Gewicht bekommt den Rest: Summe der Anteile = Erlös, exakt
            let lastWeighted = restWeight > 0 ? slices.count : (weights.lastIndex(where: { $0 > 0 }) ?? -1)
            for (i, s) in slices.enumerated() {
                let w = weights[i]
                var share: Decimal?
                if let proceeds, let left = proceedsLeft {
                    if w <= 0 { share = 0 }
                    else if i == lastWeighted { share = left }
                    else { share = PortfolioDecimal.divide(proceeds * w, weightTotal) }
                    proceedsLeft = left - (share ?? 0)
                }
                var gain: Decimal?
                if let share, let cost = s.cost { gain = share - cost }
                if let gain {
                    r.realized += gain
                } else if soldParts != nil {
                    r.unknownSold += w
                } else {
                    r.unknownSold += s.amount == removed ? sold : PortfolioDecimal.divide(sold * s.amount, removed)
                }
                r.disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time,
                    lotTxId: s.txId, acquiredTime: s.acquired, lotKind: s.kind,
                    amount: s.amount, costUsdt: s.cost, proceedsUsdt: share, gainUsdt: gain,
                    holdingDays: holdingDays(from: s.acquired, to: t.time),
                    feeLeg: feeLeg, feeValueMissing: feeMissing, method: method, costParts: s.parts, wallet: wallet, swap: swap,
                    gift: gift, payment: payment, valueEstimated: estimated))
            }
            if uncovered > 0 {
                // Mehr abgegeben als erfasst: Erlös bekannt, Kaufpreis fehlt (nicht im realisierten Gewinn)
                r.disposals.append(PortfolioDisposal(
                    coin: symbol, sellTxId: t.id, sellTime: t.time,
                    lotTxId: nil, acquiredTime: nil, lotKind: nil,
                    amount: uncovered, costUsdt: nil, proceedsUsdt: proceedsLeft, gainUsdt: nil,
                    holdingDays: nil, feeLeg: feeLeg, feeValueMissing: feeMissing, method: method,
                    uncovered: true, wallet: wallet, swap: swap, gift: gift, payment: payment, valueEstimated: estimated))
            }
        }

        // Anfangsposten (Übergang): mit Erwerb und Rest-Einstand in ihre Wallet
        for l in seed where l.amount > 0 {
            insert(queue(l.wallet), Lot(txId: l.txId, acquired: l.acquiredTime, time: l.acquiredTime, kind: l.kind,
                                        amount: l.amount, cost: l.costUsdt))
        }

        for e in events {
            let t = e.trade
            let amount = t.amountExact
            guard amount > 0 else { continue }
            if e.feeLeg {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                let q = queue(PortfolioWallets.of(e))
                let fee = t.thirdFeeAmount
                check(q, fee, t, feeLeg: true)
                let slices = consume(q, fee)
                let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
                dispose(t, q, slices, uncovered: fee - removed, proceeds: t.thirdFeePrice.map { fee * $0 }, sold: removed,
                        feeLeg: true, feeMissing: false)
                r.soldTotal += removed
                continue
            }
            if e.swapIn {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); die Gebühr im
                // erhaltenen Coin senkt nur die Menge (Einstand je Coin steigt)
                let received = t.swapReceived
                if received <= 0 {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, queue(t.exchange), .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                insert(queue(t.exchange), Lot(txId: t.id, acquired: t.time, time: t.time, kind: .SWAP, amount: received,
                                              cost: t.swapValue))
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
                    expense(e, queue(t.exchange), .CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                let cost = price.map { amount * $0 + t.quoteFeeExact + (feeValue ?? 0) }
                if price != nil && feeValue == nil { r.feeValueMissing = true }
                insert(queue(t.exchange), Lot(txId: t.id, acquired: t.acquisitionTime, time: t.time, kind: t.type,
                                              amount: received, cost: cost))
            case .SELL, .PAYMENT, .SWAP:
                // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                let swap = t.type == .SWAP
                let q = queue(t.exchange)
                let requested = amount + t.coinFeeExact
                check(q, requested, t, feeLeg: false)
                let slices = consume(q, requested)
                let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
                // Zuerst die verkaufte Menge, danach die Gebühr-Coins (FIFO in einem Zug): verkaufter Teil je Posten
                var before: Decimal = 0
                var soldParts: [Decimal] = []
                for s in slices {
                    soldParts.append(min(max(amount - before, 0), s.amount))
                    before += s.amount
                }
                let sold = soldParts.reduce(Decimal(0), +)
                // Erlös des ganzen Abgangs (Tausch: sein Wert) nur nach verkaufter Menge verteilt (Gebühr-Coins: Erlös 0)
                // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                let gross: Decimal? = swap ? (t.swapToCoin != nil ? t.swapValue : nil) : price.map { amount * $0 }
                let proceeds = gross.map { $0 - t.quoteFeeExact - (feeValue ?? 0) }
                let feeMissing = gross != nil && feeValue == nil
                if feeMissing && removed > 0 { r.realizedFeeMissing = true }
                if feeMissing { r.feeValueMissing = true }
                dispose(t, q, slices, uncovered: requested - removed, proceeds: proceeds, sold: sold, feeLeg: false,
                        feeMissing: feeMissing, swap: swap, soldParts: soldParts, uncoveredSold: amount - sold)
                r.soldTotal += sold
            case .WITHDRAWAL, .GIFT:
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                // Coins verlassen das Portfolio: älteste Posten zuerst, kein Gewinn, keine Veräusserung
                let q = queue(t.exchange)
                let requested = amount + t.coinFeeExact
                check(q, requested, t, feeLeg: false)
                if q.held <= 0 { continue }
                // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                expense(e, q, .WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings: q.held))
                let slices = consume(q, requested)
                let removed = slices.reduce(Decimal(0)) { $0 + $1.amount }
                let sold = removed == requested ? amount : PortfolioDecimal.divide(removed * amount, requested)
                r.withdrawn += sold
                r.withdrawals.append(PortfolioWithdrawalCost(
                    trade: t, sold: sold, removed: removed,
                    pricedRemoved: slices.filter { $0.cost != nil }.reduce(Decimal(0)) { $0 + $1.amount },
                    cost: slices.reduce(Decimal(0)) { $0 + ($1.cost ?? 0) }))
            case .TRANSFER:
                let src = queue(t.transferFrom)
                let dst = queue(t.transferTo)
                let coinFee = t.coinFeeExact
                if src !== dst {
                    // Je Wallet: Posten wandern mit Kaufdatum und Einstand von der Quelle zum Ziel
                    check(src, amount + coinFee, t, feeLeg: false)
                    if src.held <= 0 { continue }
                    // Netzwerkgebühr zuerst (älteste Posten der Quelle); ihr Einstand wandert mit
                    let carried = consume(src, coinFee)
                    let moved = consume(src, amount).map {
                        Lot(txId: $0.txId, acquired: $0.acquired, time: $0.time, kind: $0.kind, amount: $0.amount, cost: $0.cost,
                            parts: $0.parts)
                    }
                    // Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf die verschobenen Teile; der Anteil ohne
                    // Kaufpreis wird vermerkt
                    if feeValue == nil && moved.contains(where: { $0.cost != nil }) { r.feeValueMissing = true }
                    unassign(t, spread(moved, t.quoteFeeExact + (feeValue ?? 0), moved.reduce(Decimal(0)) { $0 + $1.amount },
                                       at: t.time))
                    // Nächster Posten mit Kaufpreis: verschoben, Rest der Quelle, sonst im Ziel; erst ohne jeden
                    // wartet der Einstand (bei den verschobenen Coins im Ziel, sonst in der Quelle)
                    let target = moved.first(where: { $0.cost != nil }) ?? src.lots.first(where: { $0.cost != nil })
                        ?? dst.lots.first(where: { $0.cost != nil })
                    carry(target, moved.isEmpty ? src : dst, t, carried)
                    for lot in moved { insert(dst, lot) }
                    continue
                }
                let q = src
                let h = q.held
                if h <= 0 { continue }
                // Gebühr USDT + Wert der Gebühr im dritten Coin: nach Menge auf die Posten verteilt; der Anteil der Posten
                // ohne Kaufpreis wird vermerkt
                if feeValue == nil && q.lots.contains(where: { $0.cost != nil }) { r.feeValueMissing = true }
                unassign(t, spread(q.lots, t.quoteFeeExact + (feeValue ?? 0), h, at: t.time))
                // Netzwerkgebühr im Coin: Menge sinkt (älteste zuerst), ihr Einstand geht auf den nächsten Posten mit
                // Kaufpreis über (Kosten des Haltens, keine Veräusserung)
                if coinFee > h { check(q, coinFee, t, feeLeg: false) }
                if coinFee > 0 {
                    let carried = consume(q, coinFee)
                    carry(q.lots.first(where: { $0.cost != nil }), q, t, carried)
                }
            }
        }

        // Gebühr-Einstand, für den bis zum Ende kein Posten mit Kaufpreis kam: vermerkt (zeitlich, stabil wie Android)
        let lost: [PortfolioLostFeeCost] = r.lostFeeCosts + queues.flatMap(\.pendingLost)
        r.lostFeeCosts = lost.enumerated().sorted { a, b in
            if a.element.time != b.element.time { return a.element.time < b.element.time }
            if a.element.txId != b.element.txId { return a.element.txId < b.element.txId }
            return a.offset < b.offset
        }.map(\.element)
        var outLots: [PortfolioLot] = []
        for q in queues {
            for l in q.lots {
                if let cost = l.cost {
                    r.priced += l.amount
                    r.cost += cost
                } else {
                    r.unpriced += l.amount
                }
                outLots.append(PortfolioLot(coin: symbol, txId: l.txId, acquiredTime: l.acquired, kind: l.kind,
                                            amount: l.amount, costUsdt: l.cost, wallet: walletOf(q)))
            }
        }
        r.lots = outLots
        return r
    }
}
