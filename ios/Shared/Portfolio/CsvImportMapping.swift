import Foundation

/// Importierte Zeile als Portfolio-Transaktion (vor dem Speichern) — wie `CsvImportTx` (Kotlin).
struct CsvImportTx: Equatable {
    let line: Int
    let coin: String
    let type: PortfolioTxType
    let amount: Decimal
    let priceUsdt: Decimal?
    let time: Int64
    let fee: Decimal?
    let feeUnit: PortfolioFeeUnit?
    let feeCoin: String?
    let feeCoinAmount: Decimal?
    let note: String?
    let importKey: String
    /// Umbuchung: wohin (`applyWithdrawalChoices`: passende beobachtete Adresse; verknüpfte Umbuchung `CsvTransferMatch`:
    /// Börse der Einzahlung); nil = keine Angabe.
    var transferTo: String? = nil
    /// Verknüpfte Umbuchung (`CsvTransferMatch`): Börse der Auszahlung; nil = keine Angabe.
    var transferFrom: String? = nil
    /// Tausch (`.SWAP`): erhaltener Coin und Menge, Wert in USDT (nil = unbekannt).
    var toCoin: String? = nil
    var toAmount: Decimal? = nil
    var swapValueUsdt: Decimal? = nil
    /// Verknüpfte Umbuchung zweier neuer Zeilen (`CsvTransferMatch.apply`): Herkunft der Einzahlung; sonst nil.
    var linkedImportKey: String? = nil
    /// Gebühr-Zeile (CSV «Fee»): Veräusserung zum Tageskurs, gespeichert als Verkauf mit Merker — wie Kotlin.
    var feeRow = false
    /// Belohnung, die die Börse als Zins (Lending) ausweist (`TaxEngines.interestReward` auf der Art der Datei).
    var interest = false
}

/// Vorschau eines Imports — wie `CsvImportPreview` (Kotlin).
struct CsvImportPreview {
    let format: CsvImportFormat
    let transactions: [CsvImportTx]
    let duplicates: Int
    let skipped: [CsvImportSkip]
    /// Zeilen ohne Tageskurs (ohne Preis bzw. Gebühr übernommen).
    let missingFx: [Int]
    /// Kauf/Verkauf ohne Preis in der Datei (ohne Preis übernommen).
    let missingPrice: [Int]
    /// Zeitzone der Zeiten ohne Versatz (`CsvImportResult.zone`) und ob es solche gibt (Auswahl in der Vorschau).
    var zone: String = CsvImportParser.defaultZone
    var localTimes = false
    /// Reihenfolge Tag/Monat (`CsvImportResult.dateOrder`) und ob die Datei sie offen lässt (Auswahl in der Vorschau).
    var dateOrder: CsvDateOrder = .DAY_FIRST
    var dateOrderAmbiguous = false
    /// Mindestens eine neue Zeile ist ein Kauf/Verkauf gegen Stablecoin, gebucht als Tausch (`CsvImportRow.stableSwap`).
    var stableSwaps = false
    /// Mehrdeutige Zahl und gelesenes Dezimalzeichen (`CsvImportResult.decimalSample`); Auswahl in der Vorschau.
    var decimalSample: String? = nil
    var decimalMark: Character? = nil
    /// Erkannte Spalten (`CsvImportResult.columns`).
    var columns: [String] = []
    /// Zeilen, deren USD-/Fiat-Betrag mit USDT = 1 USD umgerechnet ist (kein USDT/USD-Tagesschluss DES Tags,
    /// `CsvImportPriced.usdtApproximate`) — die Vorschau nennt sie wie `missingFx`. Wie Android.
    var usdtApproximate: [Int] = []
    /// Datei über den Grenzen des Zerlegens (`CsvImportResult.tooLarge`): keine Zeilen, Meldung «zu gross».
    var tooLarge = false
}

/// Wahl je Auszahlung der Vorschau (`CsvImportMapping.proposeWithdrawals`) — wie Kotlin.
enum CsvWithdrawalChoice: String, Sendable {
    /// «Auf eigene Wallet (Umbuchung)»: bleibt im Portfolio.
    case TRANSFER
    /// «Verlässt das Portfolio (Auszahlung)».
    case WITHDRAWAL
}

/// Vorschlag für eine Auszahlung (`CsvImportTx.importKey`): `choice` nil = keine Vorauswahl (muss gewählt werden);
/// `transferTo` = Ziel der Umbuchung (Bezeichnung bzw. gekürzte Adresse der passenden beobachteten Adresse). Wie Kotlin.
struct CsvWithdrawalProposal: Equatable, Sendable {
    let importKey: String
    let choice: CsvWithdrawalChoice?
    let transferTo: String?
}

/// Zuordnung importierter Zeilen zu den Buchungsarten des Portfolios — gleiche Regeln wie
/// `CsvImportMapping.kt`: Kauf/Verkauf/Belohnung/Einzahlung gleich, Auszahlung → Auszahlung, Gebühr-Zeile →
/// Veräusserung zum Tageskurs (gespeichert als Verkauf zum Preis 0 = Kennzeichen, `PortfolioLedger.isFeeDisposal`: Erlös =
/// Menge × Tagesschluss ihres Tags, ohne Kurs unbekannt — nie 0; mit der Art als Notiz); Gebühr im
/// Coin → Gebühr in Coin, Tausch mit Gebühr im erhaltenen Coin (auch USDT) → Gebühr im erhaltenen Coin (feeCoin = toCoin),
/// in Fiat/USD → USDT, in anderem Coin → Gebühr im dritten Coin; Fiat-Gebühr ohne Tageskurs
/// fällt weg (die Zeile steht dann unter `missingFx`). Option `withdrawalsAsTransfer`: Auszahlungen werden Umbuchungen (Ziel aus der Datei, die Formate liefern
/// keins → nil); Gebühr-Zeilen bleiben Veräusserungen zum Tageskurs.
/// Tausch-Zeile → Tausch mit erhaltenem Coin/Menge, Wert aus dem Fiat-Betrag der Datei (sonst unbekannt).
enum CsvImportMapping {

    private static let noteMax = 120

    /// Zeilen je Block beim Umrechnen der Vorschau (`preview`): dazwischen Fortschritt — wie Android `PREVIEW_BLOCK`.
    static let previewBlock = 100

    static func type(_ kind: CsvImportKind, withdrawalsAsTransfer: Bool = false) -> PortfolioTxType {
        switch kind {
        case .BUY: return .BUY
        case .SELL: return .SELL
        case .REWARD: return .REWARD
        case .DEPOSIT: return .DEPOSIT
        case .WITHDRAWAL: return withdrawalsAsTransfer ? .TRANSFER : .WITHDRAWAL
        // Gebühr: Veräusserung zum Tageskurs (Kennzeichen Preis 0 in `toTx`, bewertet beim Rechnen) — wie Android
        case .FEE: return .SELL
        case .SWAP: return .SWAP
        // Eigene Mittel zurück aus Staking/Earn: bleibt im Portfolio, kein Gewinn
        case .TRANSFER: return .TRANSFER
        }
    }

    static func toTx(_ p: CsvImportPriced, withdrawalsAsTransfer: Bool = false) -> CsvImportTx {
        let row = p.row
        let coin = PortfolioCalculator.normalizeCoin(row.coin)
        let unit = row.feeUnit.map { PortfolioCalculator.normalizeCoin($0) }
        var fee: Decimal?
        var feeUnit: PortfolioFeeUnit?
        var feeCoin: String?
        var feeCoinAmount: Decimal?
        // Tausch: erhaltener Coin (auch USDT/Stablecoin) — Gebühr darin mindert die erhaltene Menge, nicht den Erlös
        var swapTo: String?
        if row.kind == .SWAP, let t = row.toCoin.map({ PortfolioCalculator.normalizeCoin($0) }), !t.isEmpty, t != coin {
            swapTo = t
        }
        if let rowFee = row.fee, rowFee > 0, let unit {
            if unit == coin {
                fee = rowFee
                feeUnit = .COIN
            } else if unit == swapTo {
                feeCoin = unit
                feeCoinAmount = rowFee
            } else if let usdt = p.feeUsdt {
                if usdt > 0 {
                    fee = usdt
                    feeUnit = .QUOTE
                }
            } else if !CsvImport.isFiat(unit) && unit != row.quote && unit != "USDT" {
                // Jede Fiat-Währung mit Tageskurs (`CsvImport.isFiat`) ist Geld, nie ein «dritter Coin»
                feeCoin = unit
                feeCoinAmount = rowFee
            }
            // sonst: Fiat-Gebühr ohne Tageskurs — weggelassen, die Zeile steht unter missingFx (`CsvImport.priceInUsdt`)
        }
        var note: String?
        // Gebühr/Umbuchung: Art als Notiz; Belohnung nur als Zins (AT: Anschaffungskosten = Wert, wie Android)
        if row.kind == .FEE || row.kind == .TRANSFER || (row.kind == .REWARD && TaxEngines.interestReward(row.rawKind)) {
            let t = String(row.rawKind.trimmingCharacters(in: .whitespacesAndNewlines).prefix(noteMax))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            note = t.isEmpty ? nil : t
        }
        return CsvImportTx(line: row.line, coin: coin, type: type(row.kind, withdrawalsAsTransfer: withdrawalsAsTransfer),
                           amount: row.amount,
                           priceUsdt: row.kind == .FEE ? Decimal(0)
                               : (row.kind == .TRANSFER ? nil : p.priceUsdt.flatMap { $0 >= 0 ? $0 : nil }),
                           time: row.timeMillis,
                           fee: fee, feeUnit: feeUnit, feeCoin: feeCoin, feeCoinAmount: feeCoinAmount,
                           note: note, importKey: row.key,
                           toCoin: row.kind == .SWAP ? row.toCoin.map { PortfolioCalculator.normalizeCoin($0) } : nil,
                           toAmount: row.kind == .SWAP ? row.toAmount : nil,
                           swapValueUsdt: row.kind == .SWAP ? p.swapValueUsdt.flatMap { $0 >= 0 ? $0 : nil } : nil,
                           // Eigene Merker: Gebühr-Zeile und Zins — die Notiz bleibt nur zur Anzeige
                           feeRow: row.kind == .FEE,
                           interest: row.kind == .REWARD && TaxEngines.interestReward(row.rawKind))
    }

    static func preview(_ result: CsvImportResult, existingKeys: Set<String>,
                        zone: String? = nil, withdrawalsAsTransfer: Bool = false,
                        usdtUsd: [Int: Decimal]? = nil,
                        fx: (String, Int64) -> Decimal?) -> CsvImportPreview {
        preview(result, existingKeys: existingKeys, zone: zone, withdrawalsAsTransfer: withdrawalsAsTransfer,
                usdtUsd: usdtUsd, onRow: { _, _ in }, fx: fx)
    }

    /// Vorschau: Doppelte abtrennen, die neuen umrechnen und zuordnen. Umgerechnet wird in Blöcken zu `previewBlock`
    /// Zeilen (jede Zeile für sich, das Ergebnis ist dasselbe); nach jedem Block `onRow` (fertig, gesamt) — wie Android
    /// `preview` mit `onRow`.
    static func preview(_ result: CsvImportResult, existingKeys: Set<String>,
                        zone: String? = nil, withdrawalsAsTransfer: Bool = false,
                        usdtUsd: [Int: Decimal]? = nil,
                        onRow: (_ done: Int, _ total: Int) -> Void,
                        fx: (String, Int64) -> Decimal?) -> CsvImportPreview {
        let split = CsvImport.splitDuplicates(result.rows, existingKeys: existingKeys)
        // Binance: fast gleiche Geschäfte wie schon importierte in die Übersprungenen statt doppelt (wie Android)
        let held: Set<String> = result.format == .BINANCE
            ? Set(CsvImportBinance.possibleDuplicates(split.fresh, existingKeys: existingKeys).map(\.key)) : []
        let fresh = split.fresh.filter { !held.contains($0.key) }
        var skipped = result.skipped + split.fresh.filter { held.contains($0.key) }
            .map { CsvImportSkip(line: $0.line, reason: .POSSIBLE_DUPLICATE) }
        // stabil nach Zeile (wie Android `sortedBy`)
        skipped = skipped.enumerated().sorted { ($0.element.line, $0.offset) < ($1.element.line, $1.offset) }.map(\.element)
        // Ohne Angabe: Zeitzone der Tageskurse (`result.fxZone` = Gerät, wie der Steuer-Export), wie Android
        var priced: [CsvImportPriced] = []
        priced.reserveCapacity(fresh.count)
        for start in stride(from: 0, to: fresh.count, by: previewBlock) {
            let block = Array(fresh[start..<min(start + previewBlock, fresh.count)])
            priced += CsvImport.priceInUsdt(block, zone: zone ?? result.fxZone, usdtUsd: usdtUsd, fx: fx)
            onRow(priced.count, fresh.count)
        }
        return CsvImportPreview(format: result.format,
                                transactions: priced.map { toTx($0, withdrawalsAsTransfer: withdrawalsAsTransfer) },
                                duplicates: split.duplicates.count, skipped: skipped,
                                missingFx: priced.filter(\.needsFx).map(\.row.line),
                                missingPrice: priced.filter(\.missingPrice).map(\.row.line),
                                zone: result.zone, localTimes: result.localTimes,
                                dateOrder: result.dateOrder, dateOrderAmbiguous: result.dateOrderAmbiguous,
                                stableSwaps: fresh.contains { $0.stableSwap },
                                decimalSample: result.decimalSample, decimalMark: result.decimalMark,
                                columns: result.columns,
                                usdtApproximate: priced.filter(\.usdtApproximate).map(\.row.line),
                                tooLarge: result.tooLarge)
    }

    /// Reihenfolge beim Speichern (die Id folgt ihr und entscheidet bei gleicher Zeit) — wie Android `insertOrder`: Datei
    /// «neueste zuerst» (mehr absteigende als aufsteigende Schritte der Zeit) → umgekehrt; sonst wie in der Datei.
    static func insertOrder(_ txs: [CsvImportTx]) -> [CsvImportTx] {
        var down = 0
        var up = 0
        for i in txs.indices.dropFirst() {
            if txs[i].time < txs[i - 1].time { down += 1 } else if txs[i].time > txs[i - 1].time { up += 1 }
        }
        return down > up ? Array(txs.reversed()) : txs
    }

    /// Toleranz des Vorschlags «Umbuchung»: ± 1 % der beobachteten Änderung …
    private static let matchTolerance: Decimal = Decimal(string: "0.01") ?? 0

    /// … mindestens aber ein Staubrest (Rundung der Explorer bei sehr kleinen Mengen).
    private static let matchDust: Decimal = Decimal(string: "0.00000001") ?? 0

    /// Die beobachtete Änderung liegt höchstens so lange nach der Auszahlung (Bestätigungen, seltene Abfragen).
    static let matchWindowMillis: Int64 = 7 * 24 * 60 * 60 * 1000

    /// Vorschlag je Auszahlung der Vorschau (`.WITHDRAWAL`, Reihenfolge wie `txs`): gibt es eine ZÄHLENDE beobachtete
    /// Adresse dieses Coins, deren beobachtete Änderung (erste Beobachtung, Zunahme zwischen zwei Beobachtungen,
    /// `AddressBalanceHistory.of`) der Menge entspricht (Abweichung höchstens max(1 % der Änderung, `matchDust`); Menge oder
    /// Menge − Gebühr im Coin) und frühestens zur Zeit der Auszahlung, spätestens `matchWindowMillis` danach beobachtet
    /// wurde → Umbuchung mit dieser Adresse als Ziel (erste passende in der Reihenfolge der Adressen); sonst keine
    /// Vorauswahl. Jede beobachtete Änderung passt zu höchstens EINER Auszahlung. Wie Kotlin.
    static func proposeWithdrawals(_ txs: [CsvImportTx], addresses: [WatchedAddress]) -> [CsvWithdrawalProposal] {
        // Zunahmen je Coin mit Zeit und Ziel (nur zählende Adressen: «Nur anzeigen» hat keinen Verlauf)
        var changes: [(coin: String, delta: Decimal, time: Int64, name: String)] = []
        for a in addresses where a.countInPortfolio {
            let name: String = a.label ?? WatchedAddressRules.shortAddress(a.address)
            for h in AddressBalanceHistory.of([a]) {
                var before: Decimal = 0
                for o in h.observations.sorted(by: { $0.time < $1.time }) {
                    let delta: Decimal = o.balance - before
                    if delta > 0 {
                        changes.append((coin: PortfolioCalculator.normalizeCoin(h.coin), delta: delta, time: o.time, name: name))
                    }
                    before = o.balance
                }
            }
        }
        var used = Set<Int>()
        return txs.filter { $0.type == .WITHDRAWAL }.map { tx -> CsvWithdrawalProposal in
            let coinFee: Decimal = tx.feeUnit == .COIN ? (tx.fee ?? 0) : 0
            let amounts: [Decimal] = [tx.amount, tx.amount - coinFee].filter { $0 > 0 }
            let hit = changes.indices.first { i -> Bool in
                let c = changes[i]
                guard !used.contains(i), c.coin == tx.coin, c.time >= tx.time, c.time - tx.time <= matchWindowMillis else {
                    return false
                }
                let limit: Decimal = max(c.delta * matchTolerance, matchDust)
                return amounts.contains { x -> Bool in
                    let diff: Decimal = x - c.delta
                    return abs(diff) <= limit
                }
            }
            if let hit { used.insert(hit) }
            return CsvWithdrawalProposal(importKey: tx.importKey, choice: hit == nil ? nil : .TRANSFER,
                                         transferTo: hit.map { changes[$0].name })
        }
    }

    /// Jede Auszahlung der Vorschau hat eine Wahl (`choices` je `CsvImportTx.importKey`) — erst dann «Importieren».
    static func allWithdrawalsDecided(_ txs: [CsvImportTx], choices: [String: CsvWithdrawalChoice]) -> Bool {
        txs.allSatisfy { $0.type != .WITHDRAWAL || choices[$0.importKey] != nil }
    }

    /// Wahl übernehmen: Auszahlungen mit `.TRANSFER` werden Umbuchungen (Ziel aus `destinations`, sonst nil), die übrigen
    /// bleiben Auszahlungen. Andere Arten unverändert. Wie Kotlin.
    static func applyWithdrawalChoices(_ txs: [CsvImportTx], choices: [String: CsvWithdrawalChoice],
                                       destinations: [String: String?] = [:]) -> [CsvImportTx] {
        txs.map { tx -> CsvImportTx in
            guard tx.type == .WITHDRAWAL, choices[tx.importKey] == .TRANSFER else { return tx }
            let destination: String? = destinations[tx.importKey] ?? nil
            return CsvImportTx(line: tx.line, coin: tx.coin, type: .TRANSFER, amount: tx.amount, priceUsdt: tx.priceUsdt,
                               time: tx.time, fee: tx.fee, feeUnit: tx.feeUnit, feeCoin: tx.feeCoin,
                               feeCoinAmount: tx.feeCoinAmount, note: tx.note, importKey: tx.importKey,
                               transferTo: destination, transferFrom: tx.transferFrom, toCoin: tx.toCoin, toAmount: tx.toAmount,
                               swapValueUsdt: tx.swapValueUsdt)
        }
    }

    /// Währungen mit frühestem und spätestem Tag (Tage seit 1970-01-01), ohne USD/USDT.
    static func fxNeeds(_ rows: [CsvImportRow], zone: String = CsvImportParser.defaultZone) -> [String: ClosedRange<Int64>] {
        var out: [String: ClosedRange<Int64>] = [:]
        for row in rows {
            let day = CsvImport.epochDay(row.timeMillis, zone: zone)
            var currencies: [String] = []
            if row.price != nil || row.swapValue != nil, let q = row.quote { currencies.append(q) }
            // Gebühr in jeder Fiat-Währung mit Tageskurs, auch einer anderen als der Preiswährung (`CsvImport.priceInUsdt`)
            if row.fee != nil, let u = row.feeUnit, u == row.quote || CsvImport.isFiat(u) { currencies.append(u) }
            for c in currencies where c != "USD" && c != "USDT" {
                if let r = out[c] {
                    out[c] = min(r.lowerBound, day)...max(r.upperBound, day)
                } else {
                    out[c] = day...day
                }
            }
        }
        return out
    }
}
