import Foundation

/// Gespeicherte, importierte Auszahlung, Einzahlung oder Umbuchung ohne Gegenstück — mögliche zweite Seite einer neuen
/// Zeile (`CsvTransferMatch.candidates`). Nur importierte Transaktionen: von Hand erfasste Einzahlungen tragen oft bewusst
/// einen Kaufpreis oder ein Kaufdatum, das eine Umbuchung verwerfen würde. Die Felder ab `priceUsdt` braucht nur der Zustand
/// für «Import rückgängig» (`CsvTransferMatch.restoreState`); `linkedImportKey` gesetzt = schon verknüpft. `stakingMove`:
/// Staking-Umbuchung aus der Datei («Unstaking», «Redeem»), nie eine Auszahlung (`CsvTransferMatch.storedWithdrawal`).
/// Wie Android `CsvStoredMove`.
struct CsvStoredMove: Equatable {
    let id: Int64
    let coin: String
    let type: PortfolioTxType
    let amount: Decimal
    let fee: Decimal?
    let feeUnit: PortfolioFeeUnit?
    let feeCoin: String?
    let feeCoinAmount: Decimal?
    let time: Int64
    let importKey: String
    let transferFrom: String?
    let transferTo: String?
    var priceUsdt: Decimal? = nil
    var feeCoinPrice: Decimal? = nil
    var acquiredTime: Int64? = nil
    var exchange: String? = nil
    var importBatch: String? = nil
    var linkedImportKey: String? = nil
    var stakingMove = false
}

/// Importierte Transaktion für «Import rückgängig» (`CsvTransferMatch.undoPlan`): Id, Importlauf, Zustand vor einer
/// Verknüpfung.
struct CsvImportedRef: Equatable {
    let id: Int64
    let importBatch: String?
    let restoreState: CsvRestoreState?
}

/// Was «Import rückgängig» tut: `delete` löschen, `revert` auf den Zustand vor der Verknüpfung zurücksetzen, `detach` in die
/// aufgegangene Zeile der späteren Datei zurückverwandeln (bleibt in ihrem Lauf).
struct CsvUndoPlan: Equatable {
    let delete: Set<Int64>
    let revert: [Int64: CsvRestoreState]
    let detach: [Int64: CsvAbsorbedRow]
}

/// Vorgeschlagene Umbuchung: Auszahlung an Ort A und passende Einzahlung desselben Coins an Ort B — wie Android
/// `CsvTransferLink` (Felder dort beschrieben). `key` = Kennung in der Vorschau (`CsvTransferMatch.linkId`, nie gespeichert).
struct CsvTransferLink: Equatable, Identifiable {
    let key: String
    let coin: String
    let withdrawalKey: String
    let depositKey: String
    let withdrawalId: Int64?
    let depositId: Int64?
    let sent: Decimal
    let received: Decimal
    let fee: Decimal?
    let feeUnit: PortfolioFeeUnit?
    let feeCoin: String?
    let feeCoinAmount: Decimal?
    let withdrawalTime: Int64
    let depositTime: Int64
    let from: String?
    let to: String?
    let unambiguous: Bool
    /// Gespeicherte Seite (bei `withdrawalId` bzw. `depositId`); ihr Zustand wird der Zustand für «Rückgängig».
    var stored: CsvStoredMove? = nil
    /// Neue Zeile, die gegen `stored` aufgeht; ihr Zustand kommt ebenfalls in den Zustand für «Rückgängig».
    var absorbed: CsvImportTx? = nil

    var id: String { key }
}

/// Gespeicherte Transaktion `id` wird die verknüpfte Umbuchung — wie Android `CsvStoredTransfer`: Herkunft der Auszahlung in
/// `importKey`, der Einzahlung in `linkedImportKey`, Zustand davor in `restoreState`; die App setzt dazu den Importlauf des
/// verknüpfenden Imports, damit «Import rückgängig» dieses Laufs die Verknüpfung löst. `absorbed` = die darin aufgehende
/// neue Zeile; ohne Verknüpfung beim Speichern eigene Zeile (`CsvTransferMatch.withFallbacks`).
struct CsvStoredTransfer: Equatable {
    let id: Int64
    let amount: Decimal
    let fee: Decimal?
    let feeUnit: PortfolioFeeUnit?
    let feeCoin: String?
    let feeCoinAmount: Decimal?
    let time: Int64
    let transferFrom: String?
    let transferTo: String?
    let importKey: String
    let linkedImportKey: String
    let restoreState: CsvRestoreState
    var absorbed: CsvImportTx? = nil
}

/// Ergebnis der gewählten Umbuchungen: neue Transaktionen und Änderungen gespeicherter.
struct CsvTransferPlan {
    let transactions: [CsvImportTx]
    let storedUpdates: [CsvStoredTransfer]
}

/// Umbuchungen über Börsen und Dateien hinweg — gleiche Regeln wie Android `CsvTransferMatch` (Begründungen dort): gleicher
/// Coin, Einzahlung höchstens 72 h nach (bis 2 h vor) der Auszahlung, angekommen ≤ abgeschickt, Differenz ≤ 5 % (= Netzwerk-
/// gebühr im Coin), nicht dieselbe Börse, höchstens eine Seite gespeichert. Eindeutige vorgewählt, mehrdeutige nur gezeigt;
/// die Wahl bleibt eine Zuordnung. Die verknüpfte Umbuchung trägt beide Herkunftsschlüssel in eigenen Feldern (Auszahlung
/// `importKey`, Einzahlung `linkedImportKey`). Gegen eine gespeicherte Seite wird diese die Umbuchung und wandert in den
/// verknüpfenden Importlauf; ihr Zustand davor und der der aufgegangenen neuen Zeile stehen in `importRestoreState`
/// (`restoreState`, `CsvRestoreState`). «Import rückgängig» des späteren Laufs setzt sie zurück, der des früheren
/// verwandelt sie in die neue Zeile der späteren Datei zurück (`undoPlan`).
enum CsvTransferMatch {

    /// Zeitfenster nach der Auszahlung.
    static let windowMillis: Int64 = 72 * 3_600_000

    /// So lange darf die Einzahlung vor der Auszahlung stehen (Uhren, Zeitzonen ohne Versatz).
    static let earlyMillis: Int64 = 2 * 3_600_000

    /// Höchster Anteil der Gebühr am abgeschickten Betrag.
    static let maxFeeShare: Decimal = Decimal(string: "0.05") ?? 0

    /// Kennung eines Kandidaten in der Vorschau (Auswahl, Liste); nie gespeichert.
    static func linkId(_ withdrawalKey: String, _ depositKey: String) -> String { "\(withdrawalKey) → \(depositKey)" }

    /// Zustand der gespeicherten Seite von `link` vor der Verknüpfung samt der aufgegangenen neuen Zeile — für
    /// «Import rückgängig» beider Läufe; nil = keine gespeicherte Seite. Wie Android `restoreState`.
    static func restoreState(_ link: CsvTransferLink) -> CsvRestoreState? {
        guard let s = link.stored, let n = link.absorbed else { return nil }
        return CsvRestoreState(
            type: s.type, amount: s.amount, priceUsdt: s.priceUsdt, time: s.time, fee: s.fee, feeUnit: s.feeUnit,
            feeCoin: s.feeCoin, feeCoinAmount: s.feeCoinAmount, feeCoinPrice: s.feeCoinPrice, transferFrom: s.transferFrom,
            transferTo: s.transferTo, acquiredTime: s.acquiredTime, exchange: s.exchange, importKey: s.importKey,
            importBatch: s.importBatch,
            absorbed: CsvAbsorbedRow(type: n.type, amount: n.amount, priceUsdt: n.priceUsdt, time: n.time, fee: n.fee,
                                     feeUnit: n.feeUnit, feeCoin: n.feeCoin, feeCoinAmount: n.feeCoinAmount,
                                     importKey: n.importKey))
    }

    /// «Import rückgängig» des Laufs `batch` — wie Android `undoPlan`: eine vom Lauf verknüpfte Umbuchung mit gespeicherter
    /// Seite eines früheren Imports zurücksetzen; eine verknüpfte Umbuchung, deren gespeicherte Seite aus diesem Lauf stammt,
    /// wieder in die aufgegangene neue Zeile der späteren Datei verwandeln (ohne deren Zustand, alter Bestand:
    /// löschen); die übrigen Zeilen des Laufs löschen.
    static func undoPlan(_ rows: [CsvImportedRef], batch: String) -> CsvUndoPlan {
        var delete = Set<Int64>()
        var revert: [Int64: CsvRestoreState] = [:]
        var detach: [Int64: CsvAbsorbedRow] = [:]
        for r in rows {
            let o = r.restoreState
            if r.importBatch == batch, let o, o.importBatch != batch {
                revert[r.id] = o
            } else if r.importBatch != batch, let o, o.importBatch == batch, let absorbed = o.absorbed {
                detach[r.id] = absorbed
            } else if r.importBatch == batch || (o != nil && o?.importBatch == batch) {
                delete.insert(r.id)
            }
        }
        return CsvUndoPlan(delete: delete, revert: revert, detach: detach)
    }

    /// Gespeicherte Seite als Auszahlung: eine Auszahlung oder eine Umbuchung ohne Ziel (Wahl «Auf eigene Wallet»), die
    /// keine Staking-Umbuchung ist. Eine Staking-Umbuchung der Datei («Unstaking», «Redeem», `CsvImportKind.TRANSFER`)
    /// trägt den Merker `stakingMove` (`CsvImportMapping.toTx`, unabhängig von der Notiz) — eigene Mittel zurück aus
    /// Staking, die nie eine Börse verlassen: als Auszahlung verknüpft, ginge eine spätere echte Einzahlung darin auf und die
    /// echte Auszahlung fände kein Gegenstück mehr. Wie Android.
    static func storedWithdrawal(_ s: CsvStoredMove) -> Bool {
        s.type == .WITHDRAWAL || (s.type == .TRANSFER && s.transferTo == nil && !s.stakingMove)
    }

    /// Herkunft (Börse) aus dem Format am Anfang eines Herkunftsschlüssels («BINANCE|…» → Binance); unbekannt = nil.
    static func placeOf(_ importKey: String) -> String? {
        let format = importKey.components(separatedBy: "|").first ?? importKey
        return CsvImportFormat(rawValue: format)?.exchangeName
    }

    /// Eine Seite einer möglichen Umbuchung.
    private struct End {
        let key: String
        let id: Int64?
        let coin: String
        let time: Int64
        let place: String?
        /// Auszahlung: Menge + Gebühr im Coin (verlässt A); Einzahlung: Menge − Gebühr im Coin (kommt an).
        let total: Decimal
        let quoteFee: Decimal?
        let feeCoin: String?
        let feeCoinAmount: Decimal?
        /// Einzahlung mit Gebühr ausser im Coin (USDT, dritter Coin): nicht verknüpfbar.
        let foreignFee: Bool
        /// Gespeicherte Seite (sonst eine neue Zeile der Datei).
        var stored: CsvStoredMove? = nil
        /// Neue Zeile der Datei (sonst gespeichert).
        var tx: CsvImportTx? = nil
    }

    private static func coinFee(_ fee: Decimal?, _ unit: PortfolioFeeUnit?) -> Decimal {
        guard unit == .COIN, let fee, fee > 0 else { return 0 }
        return fee
    }

    private static func quoteFee(_ fee: Decimal?, _ unit: PortfolioFeeUnit?) -> Decimal? {
        guard unit == .QUOTE, let fee, fee > 0 else { return nil }
        return fee
    }

    private static func withdrawal(_ key: String, _ coin: String, _ amount: Decimal, _ fee: Decimal?,
                                   _ unit: PortfolioFeeUnit?, _ feeCoin: String?, _ feeCoinAmount: Decimal?, _ time: Int64,
                                   _ place: String?, stored: CsvStoredMove? = nil, tx: CsvImportTx? = nil) -> End {
        End(key: key, id: stored?.id, coin: coin, time: time, place: place, total: amount + coinFee(fee, unit),
            quoteFee: quoteFee(fee, unit), feeCoin: feeCoin, feeCoinAmount: feeCoinAmount, foreignFee: false, stored: stored,
            tx: tx)
    }

    private static func deposit(_ key: String, _ coin: String, _ amount: Decimal, _ fee: Decimal?,
                                _ unit: PortfolioFeeUnit?, _ feeCoin: String?, _ time: Int64, _ place: String?,
                                stored: CsvStoredMove? = nil, tx: CsvImportTx? = nil) -> End {
        End(key: key, id: stored?.id, coin: coin, time: time, place: place, total: amount - coinFee(fee, unit), quoteFee: nil,
            feeCoin: nil, feeCoinAmount: nil, foreignFee: quoteFee(fee, unit) != nil || feeCoin != nil, stored: stored, tx: tx)
    }

    /// Kandidaten zwischen den neuen Zeilen `txs` (aus einer Datei mit Herkunft `place`) und untereinander bzw. gegen
    /// gespeicherte Seiten `stored`; sortiert nach Zeit der Auszahlung, dann der Einzahlung — wie Android `candidates`.
    static func candidates(_ txs: [CsvImportTx], place: String?, stored: [CsvStoredMove]) -> [CsvTransferLink] {
        var outs: [End] = []
        var ins: [End] = []
        for t in txs {
            switch t.type {
            case .WITHDRAWAL:
                outs.append(withdrawal(t.importKey, t.coin, t.amount, t.fee, t.feeUnit, t.feeCoin, t.feeCoinAmount, t.time, place,
                                       tx: t))
            case .DEPOSIT:
                ins.append(deposit(t.importKey, t.coin, t.amount, t.fee, t.feeUnit, t.feeCoin, t.time, place, tx: t))
            default:
                break
            }
        }
        // Schon verknüpft (zweiter Herkunftsschlüssel): kein Kandidat mehr
        for s in stored where s.linkedImportKey == nil {
            if storedWithdrawal(s) {
                outs.append(withdrawal(s.importKey, s.coin, s.amount, s.fee, s.feeUnit, s.feeCoin, s.feeCoinAmount, s.time,
                                       s.transferFrom ?? placeOf(s.importKey), stored: s))
            } else if s.type == .DEPOSIT {
                ins.append(deposit(s.importKey, s.coin, s.amount, s.fee, s.feeUnit, s.feeCoin, s.time, placeOf(s.importKey),
                                   stored: s))
            }
        }
        var found: [CsvTransferLink] = []
        // Nur Einzahlungen desselben Coins im Zeitfenster prüfen (`fits` verlangt beides)
        let pairs = CsvTransferWindow.pairs(outs, ins, coin: \.coin, time: \.time, early: earlyMillis,
                                            window: windowMillis, fits: fits)
        for (wi, di) in pairs {
            let w = outs[wi]
            let d = ins[di]
            let diff = w.total - d.total
            let coinDiff = diff > 0
            found.append(CsvTransferLink(
                key: linkId(w.key, d.key), coin: w.coin, withdrawalKey: w.key, depositKey: d.key,
                withdrawalId: w.id, depositId: d.id, sent: w.total, received: d.total,
                fee: coinDiff ? diff : w.quoteFee,
                feeUnit: coinDiff ? .COIN : (w.quoteFee == nil ? nil : .QUOTE),
                feeCoin: w.feeCoin, feeCoinAmount: w.feeCoinAmount,
                withdrawalTime: w.time, depositTime: d.time, from: w.place, to: d.place,
                unambiguous: false, stored: w.stored ?? d.stored,
                absorbed: w.stored != nil ? d.tx : (d.stored != nil ? w.tx : nil)))
        }
        var perOut: [String: Int] = [:]
        var perIn: [String: Int] = [:]
        for l in found {
            perOut[l.withdrawalKey, default: 0] += 1
            perIn[l.depositKey, default: 0] += 1
        }
        let links = found.map { l -> CsvTransferLink in
            CsvTransferLink(
                key: l.key, coin: l.coin, withdrawalKey: l.withdrawalKey, depositKey: l.depositKey,
                withdrawalId: l.withdrawalId, depositId: l.depositId, sent: l.sent, received: l.received, fee: l.fee,
                feeUnit: l.feeUnit, feeCoin: l.feeCoin, feeCoinAmount: l.feeCoinAmount, withdrawalTime: l.withdrawalTime,
                depositTime: l.depositTime, from: l.from, to: l.to,
                unambiguous: perOut[l.withdrawalKey] == 1 && perIn[l.depositKey] == 1, stored: l.stored,
                absorbed: l.absorbed)
        }
        // stabil wie Android `sortedWith(compareBy(withdrawalTime, depositTime, key))`
        return links.sorted { a, b in
            if a.withdrawalTime != b.withdrawalTime { return a.withdrawalTime < b.withdrawalTime }
            if a.depositTime != b.depositTime { return a.depositTime < b.depositTime }
            return a.key.unicodeScalars.lexicographicallyPrecedes(b.key.unicodeScalars, by: { $0.value < $1.value })
        }
    }

    private static func fits(_ w: End, _ d: End) -> Bool {
        if w.coin != d.coin || (w.id != nil && d.id != nil) || d.foreignFee { return false }
        // Zwei neue Zeilen einer Börsen-Datei: dieselbe Börse kann nicht an sich selbst überweisen
        if let place = w.place, place == d.place { return false }
        if d.time < w.time - earlyMillis || d.time > w.time + windowMillis { return false }
        if d.total <= 0 || d.total > w.total { return false }
        let diff = w.total - d.total
        if diff > PortfolioDecimal.multiply(w.total, maxFeeShare) { return false }
        // USDT-Gebühr der Auszahlung und Differenz im Coin: die Umbuchung trägt nur eine Gebühr
        return !(diff > 0 && w.quoteFee != nil)
    }

    /// Vorauswahl: alle eindeutigen Kandidaten.
    static func defaultChoice(_ links: [CsvTransferLink]) -> Set<String> { Set(links.filter(\.unambiguous).map(\.key)) }

    /// Wahl nach einer neuen Vorschau: schon gezeigte Kandidaten (`seen`) behalten ihre Wahl, neue bekommen die Vorauswahl.
    static func carryOver(_ links: [CsvTransferLink], seen: Set<String>, chosen: Set<String>) -> Set<String> {
        Set(links.filter { seen.contains($0.key) ? chosen.contains($0.key) : $0.unambiguous }.map(\.key))
    }

    /// Kandidat `key` ein- oder ausschalten; eingeschaltet fallen Kandidaten mit derselben Aus- oder Einzahlung weg.
    static func toggle(_ links: [CsvTransferLink], chosen: Set<String>, key: String, on: Bool) -> Set<String> {
        guard on else { return chosen.subtracting([key]) }
        guard let link = links.first(where: { $0.key == key }) else { return chosen }
        let conflicts = Set(links.filter {
            $0.key != key && ($0.withdrawalKey == link.withdrawalKey || $0.depositKey == link.depositKey)
        }.map(\.key))
        return chosen.subtracting(conflicts).union([key])
    }

    /// Gewählte Umbuchungen anwenden — wie Android `apply`: zwei neue Zeilen → eine neue Umbuchung an der Stelle der
    /// Auszahlung (die Einzahlung entfällt; Herkunft beider in `importKey`/`linkedImportKey`); neue Zeile + gespeicherte
    /// Seite → die neue Zeile entfällt, die gespeicherte wird die Umbuchung (`storedUpdates`). Andere Zeilen unverändert.
    /// Menge der Umbuchung = was ankommt (`received`); die Gebühr im Coin geht zusätzlich von der Quelle ab (Konvention von
    /// `.TRANSFER` in FIFO und Wallet-Aufteilung). Ohne Differenz im Coin gleich `sent`.
    static func apply(_ txs: [CsvImportTx], links: [CsvTransferLink], chosen: Set<String>) -> CsvTransferPlan {
        let active = links.filter { chosen.contains($0.key) }
        var byWithdrawal: [String: CsvTransferLink] = [:]
        for l in active where l.withdrawalId == nil { byWithdrawal[l.withdrawalKey] = l }
        let absorbed = Set(active.filter { $0.depositId == nil }.map(\.depositKey))
        let out: [CsvImportTx] = txs.compactMap { tx in
            if let l = byWithdrawal[tx.importKey] {
                guard l.depositId == nil else { return nil }
                return CsvImportTx(line: tx.line, coin: tx.coin, type: .TRANSFER, amount: l.received, priceUsdt: nil,
                                   time: l.withdrawalTime, fee: l.fee, feeUnit: l.feeUnit, feeCoin: l.feeCoin,
                                   feeCoinAmount: l.feeCoinAmount, note: nil, importKey: l.withdrawalKey,
                                   transferTo: l.to, transferFrom: l.from, linkedImportKey: l.depositKey)
            }
            return absorbed.contains(tx.importKey) ? nil : tx
        }
        let updates: [CsvStoredTransfer] = active.compactMap { l in
            guard let id = l.withdrawalId ?? l.depositId, let state = restoreState(l) else { return nil }
            return CsvStoredTransfer(id: id, amount: l.received, fee: l.fee, feeUnit: l.feeUnit, feeCoin: l.feeCoin,
                                     feeCoinAmount: l.feeCoinAmount, time: l.withdrawalTime, transferFrom: l.from,
                                     transferTo: l.to, importKey: l.withdrawalKey, linkedImportKey: l.depositKey,
                                     restoreState: state, absorbed: l.absorbed)
        }
        return CsvTransferPlan(transactions: out, storedUpdates: updates)
    }
}
