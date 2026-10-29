import Foundation

/// Kraken «Ledgers»-Export (History › Export › Ledgers) → `CsvImportRow`. Wie Android `CsvImportKraken`.
///
/// Spalten: txid, refid, time (UTC), type, subtype, aclass, asset, (wallet,) amount, fee, balance. `amount` ist
/// brutto, `fee` wird zusätzlich im selben Asset abgezogen.
///  - type «trade», «spend», «receive»: zwei Zeilen mit gleicher refid = ein Geschäft. Krypto (auch Stablecoin) gegen
///    Fiat (`CsvImport.fiat`) → Kauf bzw. Verkauf (Preis = Fiat-Betrag / Menge, ohne Gebühr); Krypto gegen Krypto →
///    Tausch, auch gegen Stablecoins (`CsvImport.stables`; Wert nur mit USDT-Seite = USDT-Menge, sonst unbekannt).
///    Gebühren gleicher Einheit zusammen; verschiedene → eine am Geschäft, die andere eigene Gebühr-Zeile («refid/fee»,
///    `CsvImport.splitFees`). Fiat gegen Fiat → `.NOT_CRYPTO`.
///  - «deposit» / «withdrawal» (Krypto, auch Stablecoins) → Einzahlung / Auszahlung; Fiat → `.NOT_CRYPTO`.
///  - «staking», oder «earn» mit subtype «reward» → Belohnung (nur Zugänge).
///  - Umbuchungen zwischen Kraken-Konten, Margin, Rollover, Anpassungen → `.UNSUPPORTED_KIND`; ebenso Spot ↔ Staking als
///    Ein-/Auszahlung (Variante «DOT.S» und ihr Gegenstück) und spend/receive/trade mit gleichem Basis-Asset — wie Android.
///    Eine Staking-Zeile ohne Gegenstück → `.STAKING_UNPAIRED` («Bestand prüfen»).
///  - Doppelte Ledger-Zeilen (gleiche txid) zählen einmal; gleiche txid mit ANDERER Menge → `.DUPLICATE_TXID_DIFFERENT`
///    (nicht still verwerfen).
///  - Ein-/Auszahlung zuerst ausstehend (txid und balance leer), dann gebucht, gleiche refid: die ausstehende Zeile
///    fällt still weg, wenn die gebuchte (gleiche refid, Typ, Asset, Menge) da ist (`pendingTwins`); allein zählt sie.
///    Mit ANDERER Menge bleiben beide, als `pendingDiffers` markiert (die Vorschau nennt beide Zeilen).
///  - Kauf/Verkauf mit Fiat-Gebühr und Gebühr im Coin: Fiat-Gebühr im Preis, die im Coin am Geschäft
///    (`CsvImport.fiatFeeInPrice`); sonst andere Einheiten → eine am Geschäft, die andere eigene Gebühr-Zeile.
enum CsvImportKraken {

    /// Alte Kraken-Kürzel mit X-/Z-Vorsatz → Kürzel (wie Android `LEGACY`).
    private static let legacy: [String: String] = [
        "XXBT": "BTC", "XBT": "BTC", "XBT.M": "BTC", "XETH": "ETH", "XLTC": "LTC", "XXRP": "XRP",
        "XXLM": "XLM", "XETC": "ETC", "XZEC": "ZEC", "XXMR": "XMR", "XREP": "REP", "XMLN": "MLN",
        "XXDG": "DOGE", "XDG": "DOGE", "XICN": "ICN", "XNMC": "NMC", "XXVN": "XVN",
        "ZEUR": "EUR", "ZUSD": "USD", "ZGBP": "GBP", "ZCAD": "CAD", "ZJPY": "JPY", "ZAUD": "AUD",
        "ZCHF": "CHF", "ETH2": "ETH",
    ]

    /// Kraken-Kürzel → einheitliches Kürzel (gross); Staking-Varianten «.S», «.M», «.F» …, «.HOLD» → Basis («EUR.HOLD»
    /// = EUR, Fiat); mit Ziffern vor dem Suffix («DOT28.S») → Buchstaben davor (`stakedCore`). Wie Android.
    static func asset(_ raw: String) -> String {
        let t: String = raw.trimmingCharacters(in: .whitespaces).uppercased()
        if let mapped = legacy[t] { return mapped }
        let base = stripSuffix(t)
        if let mapped = legacy[base] { return mapped }
        if base != t, let core = stakedCore(base) { return legacy[core] ?? core }
        return base
    }

    /// Suffix einer Staking-/Earn-Variante entfernen: «.» + ein Buchstabe A–Z («.S», «.M», «.B», «.F», «.P», «.T») oder
    /// «.HOLD» (gehaltenes Guthaben); sonst unverändert — wie Android `SUFFIX`.
    private static func stripSuffix(_ t: String) -> String {
        if t.hasSuffix(".HOLD") { return String(t.dropLast(5)) }
        return hasStakingSuffix(t) ? String(t.dropLast(2)) : t
    }

    /// Nur die Staking-/Earn-Suffixe «.» + ein Buchstabe A–Z (ohne «.HOLD»: gehaltenes Guthaben ist keine
    /// Staking-Variante, `isStakedAsset`) — wie Android `STAKING_SUFFIX`.
    private static func hasStakingSuffix(_ t: String) -> Bool {
        let chars = Array(t)
        guard chars.count >= 2, chars[chars.count - 2] == ".", let last = chars.last else { return false }
        return ("A"..."Z").contains(last)
    }

    /// Basis einer Staking-Variante mit Ziffern vor dem Suffix («DOT28» aus «DOT28.S»): mindestens zwei Buchstaben A–Z,
    /// dann nur Ziffern 0–9; sonst nil — wie Android `STAKED_DIGITS`. Nur zusammen mit einem Suffix.
    private static func stakedCore(_ base: String) -> String? {
        let letters = base.prefix { ("A"..."Z").contains($0) }
        let rest = base.dropFirst(letters.count)
        guard letters.count >= 2, !rest.isEmpty, rest.allSatisfy({ ("0"..."9").contains($0) }) else { return nil }
        return String(letters)
    }

    private struct Leg {
        let line: Int
        let refid: String
        let type: String
        let subtype: String
        let asset: String
        let signed: Decimal
        let fee: Decimal?
        let time: Int64
        /// Asset ist eine Staking-/Earn-Variante («ETH2.S», «DOT.S», «ETH2»).
        var staked: Bool = false
        /// txid leer: ausstehende Buchung (Kraken schreibt die gebuchte Zeile mit txid danach).
        var pending: Bool = false
    }

    /// Staking-/Earn-Variante eines Kraken-Kürzels (vor dem Vereinheitlichen) — wie Android `isStakedAsset`.
    private static func isStakedAsset(_ raw: String) -> Bool {
        let t = raw.trimmingCharacters(in: .whitespaces).uppercased()
        return t == "ETH2" || hasStakingSuffix(t)
    }

    /// Höchster Abstand zwischen Spot- und Staking-Buchung derselben Umbuchung ohne gemeinsame refid.
    private static let stakingPairMillis: Int64 = 3_600_000

    /// Ein-/Auszahlungen zwischen Spot und Staking (Indizes in `legs`) — wie Android `stakingMoves`: zuerst für ALLE
    /// Staking-Zeilen das Gegenstück mit derselben refid (irgendwo in der Datei), erst danach für die übrigen eine Zeile
    /// ≤ 1 h daneben — sonst nähme eine frühere Staking-Zeile über das Zeitfenster die Spot-Zeile einer anderen Umbuchung
    /// weg. `unpaired`: Staking-Zeilen ohne gefundenes Gegenstück.
    private static func stakingMoves(_ legs: [Leg]) -> (moved: Set<Int>, unpaired: Set<Int>) {
        var out = Set<Int>()
        var unpaired = Set<Int>()
        func move(_ l: Leg) -> Bool { l.type == "deposit" || l.type == "withdrawal" }
        func abs(_ d: Decimal) -> Decimal { d < 0 ? -d : d }
        let staked = legs.indices.filter { move(legs[$0]) && legs[$0].staked }
        out.formUnion(staked)
        func counterpart(_ l: Leg, _ k: Int) -> Bool {
            let c = legs[k]
            return !out.contains(k) && move(c) && !c.staked && c.type != l.type && c.asset == l.asset
                && abs(c.signed) == abs(l.signed)
        }
        var open: [Int] = []
        for i in staked {
            let l = legs[i]
            let j: Int? = l.refid.isEmpty ? nil : legs.indices.first(where: { counterpart(l, $0) && legs[$0].refid == l.refid })
            if let j { out.insert(j) } else { open.append(i) }
        }
        for i in open {
            let l = legs[i]
            let j = legs.indices.first(where: { counterpart(l, $0) && Swift.abs(legs[$0].time - l.time) <= stakingPairMillis })
            if let j { out.insert(j) } else { unpaired.insert(i) }
        }
        return (out, unpaired)
    }

    /// Ausstehende Ein-/Auszahlungen (Indizes in `legs`) mit gebuchter Zeile daneben — wie Android `pendingTwins`: txid
    /// leer, und eine Zeile mit txid hat dieselbe refid, denselben Typ, dasselbe Asset (auch Staking-Variante) und dieselbe
    /// Menge. Die gebuchte Zeile zählt; ohne sie bleibt die ausstehende.
    private static func pendingTwins(_ legs: [Leg]) -> Set<Int> {
        Set(legs.indices.filter { i in
            let p = legs[i]
            guard p.pending, p.type == "deposit" || p.type == "withdrawal", !p.refid.isEmpty else { return false }
            return legs.contains { b in
                !b.pending && b.refid == p.refid && b.type == p.type && b.asset == p.asset && b.staked == p.staked
                    && b.signed == p.signed
            }
        })
    }

    /// Zeilennummern ausstehender Ein-/Auszahlungen ohne gleiche gebuchte Zeile (`twins`) und der gebuchten Zeilen mit
    /// gleicher refid, gleichem Typ und Asset, aber anderer Menge — wie Android `pendingDiffers`: beide werden gebucht,
    /// die Vorschau nennt das Paar.
    private static func pendingDiffers(_ legs: [Leg], twins: Set<Int>) -> Set<Int> {
        var out = Set<Int>()
        for (i, p) in legs.enumerated() {
            guard !twins.contains(i), p.pending, p.type == "deposit" || p.type == "withdrawal", !p.refid.isEmpty else { continue }
            let booked = legs.filter { b in
                !b.pending && b.refid == p.refid && b.type == p.type && b.asset == p.asset && b.staked == p.staked
            }
            if !booked.isEmpty {
                out.insert(p.line)
                for b in booked { out.insert(b.line) }
            }
        }
        return out
    }

    /// Erste Zeile einer txid: Menge, oder der Text, wenn unlesbar (Wiederholungen vergleichen).
    private enum FirstAmount {
        case number(Decimal)
        case text(String)
    }

    static func build(names: [String], data: [CsvImportParser.Record], zone: String) -> CsvImportResult {
        func col(_ n: String) -> Int? { names.firstIndex(of: n) }
        let iTx = col("txid")
        let iRef = col("refid"), iTime = col("time"), iType = col("type"), iSub = col("subtype")
        let iAsset = col("asset"), iAmount = col("amount"), iFee = col("fee")
        var skipped: [CsvImportSkip] = []
        var all: [Leg] = []
        var seen: [String: FirstAmount] = [:]
        for rec in data {
            func cell(_ i: Int?) -> String {
                guard let i, i < rec.cells.count else { return "" }
                return rec.cells[i]
            }
            let amount: Decimal? = CsvImportParser.parseNumber(cell(iAmount), hint: ".")
            // Wiederholte Ledger-Zeile (gleiche txid): gleiche Menge nur einmal, andere Menge in die Vorschau
            let txid = cell(iTx).trimmingCharacters(in: .whitespaces)
            if !txid.isEmpty {
                let amountText = cell(iAmount).trimmingCharacters(in: .whitespaces)
                if let first = seen[txid] {
                    let same: Bool
                    switch first {
                    case .number(let n): same = amount == n
                    case .text(let t): same = amount == nil && amountText == t
                    }
                    if !same { skipped.append(CsvImportSkip(line: rec.line, reason: .DUPLICATE_TXID_DIFFERENT)) }
                    continue
                }
                seen[txid] = amount.map { .number($0) } ?? .text(amountText)
            }
            let type: String = CsvImportParser.normalize(cell(iType))
            let sub: String = CsvImportParser.normalize(cell(iSub))
            let code: String = Self.asset(cell(iAsset))
            let feeText: String = cell(iFee)
            let fee: Decimal? = feeText.isEmpty ? Decimal(0) : CsvImportParser.parseNumber(feeText, hint: ".")
            let time: Int64? = CsvImportParser.parseTime(cell(iTime), zone: zone)
            if code.isEmpty || type.isEmpty {
                skipped.append(CsvImportSkip(line: rec.line, reason: .MISSING_FIELD))
            } else if amount == nil || fee == nil {
                skipped.append(CsvImportSkip(line: rec.line, reason: .BAD_NUMBER))
            } else if time == nil {
                skipped.append(CsvImportSkip(line: rec.line, reason: .BAD_DATE))
            } else if let amount, let fee, let time {
                let absFee: Decimal = fee < 0 ? -fee : fee
                all.append(Leg(line: rec.line, refid: cell(iRef), type: type, subtype: sub, asset: code,
                               signed: amount, fee: absFee > 0 ? absFee : nil, time: time,
                               staked: isStakedAsset(cell(iAsset)), pending: txid.isEmpty))
            }
        }
        // Ausstehende Ein-/Auszahlung mit gebuchter Zeile daneben: still weg (vor den Staking-Paaren)
        let twins = pendingTwins(all)
        let legs: [Leg] = all.indices.filter { !twins.contains($0) }.map { all[$0] }
        let differs = pendingDiffers(all, twins: twins)

        var rows: [CsvImportRow] = []
        var tradeOrder: [String] = []
        var trades: [String: [Leg]] = [:]
        let moves = stakingMoves(legs)
        for (i, leg) in legs.enumerated() {
            if moves.moved.contains(i) {
                // Spot ↔ Staking: bleibt im selben Bestand; ohne Gegenbuchung eigener Hinweis «Bestand prüfen»
                let reason: CsvImportSkipReason = moves.unpaired.contains(i) ? .STAKING_UNPAIRED : .UNSUPPORTED_KIND
                skipped.append(CsvImportSkip(line: leg.line, reason: reason))
                continue
            }
            switch leg.type {
            case "trade", "spend", "receive":
                if leg.refid.isEmpty {
                    skipped.append(CsvImportSkip(line: leg.line, reason: .MISSING_FIELD))
                } else {
                    if trades[leg.refid] == nil { tradeOrder.append(leg.refid) }
                    trades[leg.refid, default: []].append(leg)
                }
            case "deposit", "withdrawal":
                single(leg, &rows, &skipped)
            case "staking":
                reward(leg, &rows, &skipped)
            case "earn":
                if leg.subtype == "reward" { reward(leg, &rows, &skipped) }
                else { skipped.append(CsvImportSkip(line: leg.line, reason: .UNSUPPORTED_KIND)) }
            default:
                skipped.append(CsvImportSkip(line: leg.line, reason: .UNSUPPORTED_KIND))
            }
        }
        for refid in tradeOrder { trade(refid, trades[refid] ?? [], &rows, &skipped) }
        for i in rows.indices where (rows[i].kind == .DEPOSIT || rows[i].kind == .WITHDRAWAL) && differs.contains(rows[i].line) {
            rows[i].pendingDiffers = true
        }
        // Kraken schreibt UTC ohne Versatz: die Zeitzone zählt (Vorschau zeigt UTC, wählbar)
        return CsvImport.finish(.KRAKEN, rows: rows, skipped: skipped, zone: zone, localTimes: true)
    }

    /// Fiat (Stablecoins zählen als Krypto, nicht als Geld).
    private static func isMoney(_ asset: String) -> Bool { CsvImport.fiat.contains(asset) }

    private static func rawKind(_ leg: Leg) -> String { leg.subtype.isEmpty ? leg.type : leg.type + " " + leg.subtype }

    private static func single(_ leg: Leg, _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        if isMoney(leg.asset) { skipped.append(CsvImportSkip(line: leg.line, reason: .NOT_CRYPTO)); return }
        let amount: Decimal = leg.signed < 0 ? -leg.signed : leg.signed
        if amount == 0 { skipped.append(CsvImportSkip(line: leg.line, reason: .BAD_NUMBER)); return }
        let kind: CsvImportKind = leg.type == "deposit" ? .DEPOSIT : .WITHDRAWAL
        rows.append(row(leg.line, kind, rawKind(leg), leg.asset, amount, nil, nil, leg.fee,
                        leg.fee == nil ? nil : leg.asset, leg.time, leg.refid))
    }

    private static func reward(_ leg: Leg, _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        // Nur Zugänge sind Belohnungen; negative Staking-Zeilen sind Gegenbuchungen
        if leg.signed <= 0 || isMoney(leg.asset) {
            skipped.append(CsvImportSkip(line: leg.line, reason: .UNSUPPORTED_KIND))
            return
        }
        rows.append(row(leg.line, .REWARD, rawKind(leg), leg.asset, leg.signed, nil, nil, leg.fee,
                        leg.fee == nil ? nil : leg.asset, leg.time, leg.refid))
    }

    private static func trade(_ refid: String, _ group: [Leg], _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        let given = group.filter { $0.signed < 0 }
        let received = group.filter { $0.signed > 0 }
        guard given.count == 1, received.count == 1 else {
            for leg in group { skipped.append(CsvImportSkip(line: leg.line, reason: .MISSING_FIELD)) }
            return
        }
        guard given[0].asset != received[0].asset else {
            // Gleiches Basis-Asset (ETH → ETH2.S): Umbuchung ins/aus dem Staking, kein Geschäft
            for leg in group { skipped.append(CsvImportSkip(line: leg.line, reason: .UNSUPPORTED_KIND)) }
            return
        }
        let out = given[0]
        let inn = received[0]
        let outAmount: Decimal = -out.signed
        let inAmount: Decimal = inn.signed
        // Gebühren beider Seiten: gleiche Einheit zusammen; Fiat- und Coin-Gebühr eines Kaufs/Verkaufs → Fiat in den Preis;
        // sonst verschiedene → eine am Geschäft, die andere eigene Gebühr-Zeile
        var feeList: [(unit: String, amount: Decimal)] = []
        if let a = out.fee, let b = inn.fee, out.asset == inn.asset {
            feeList = [(unit: out.asset, amount: a + b)]
        } else {
            if let a = out.fee { feeList.append((unit: out.asset, amount: a)) }
            if let b = inn.fee { feeList.append((unit: inn.asset, amount: b)) }
        }
        var folded: (total: Decimal, rest: [(unit: String, amount: Decimal)])?
        if isMoney(out.asset) && !isMoney(inn.asset) {
            folded = CsvImport.fiatFeeInPrice(buy: true, coin: inn.asset, fiat: out.asset, total: outAmount, fees: feeList)
        } else if isMoney(inn.asset) && !isMoney(out.asset) {
            folded = CsvImport.fiatFeeInPrice(buy: false, coin: out.asset, fiat: inn.asset, total: inAmount, fees: feeList)
        }
        guard let split = CsvImport.splitFees(folded?.rest ?? feeList, given: out.asset, received: inn.asset) else {
            for leg in group { skipped.append(CsvImportSkip(line: leg.line, reason: .TWO_FEES)) }
            return
        }
        let fees: (fee: Decimal?, unit: String?) = (split.primary?.amount, split.primary?.unit)
        let line: Int = min(out.line, inn.line)
        if isMoney(out.asset) && isMoney(inn.asset) {
            for leg in group { skipped.append(CsvImportSkip(line: leg.line, reason: .NOT_CRYPTO)) }
        } else if isMoney(out.asset) {
            // Kauf: Geld weg, Coin rein
            let price: Decimal = PortfolioDecimal.divide(folded?.total ?? outAmount, inAmount)
            var r = row(line, .BUY, rawKind(inn), inn.asset, inAmount, price, out.asset, fees.fee, fees.unit, inn.time, refid)
            r.feeInPrice = folded != nil
            rows.append(r)
        } else if isMoney(inn.asset) {
            // Verkauf: Coin weg, Geld rein
            let price: Decimal = PortfolioDecimal.divide(folded?.total ?? inAmount, outAmount)
            var r = row(line, .SELL, rawKind(out), out.asset, outAmount, price, inn.asset, fees.fee, fees.unit, out.time, refid)
            r.feeInPrice = folded != nil
            rows.append(r)
        } else {
            // Mit Stablecoin-Seite Hinweis in der Vorschau, mit USDT-Seite Wert in USDT — wie Android
            let value = CsvImport.stableSwapValue(coin: out.asset, amount: outAmount, toCoin: inn.asset, toAmount: inAmount)
            var r = CsvImportRow(
                line: line, kind: .SWAP, rawKind: rawKind(out), coin: out.asset, amount: outAmount,
                price: nil, quote: value.quote, fee: fees.fee, feeUnit: fees.unit, timeMillis: out.time,
                externalId: refid,
                key: CsvImport.key(format: .KRAKEN, kind: .SWAP, coin: out.asset, amount: outAmount, price: nil,
                                   timeMillis: out.time, externalId: refid))
            r.toCoin = inn.asset
            r.toAmount = inAmount
            r.swapValue = value.value
            r.stableSwap = CsvImport.isStable(out.asset) || CsvImport.isStable(inn.asset)
            rows.append(r)
        }
        // Weitere Gebühr (andere Einheit): eigene Gebühr-Zeile nach dem Geschäft, eigene Kennung (die refid trägt das Geschäft)
        if !(isMoney(out.asset) && isMoney(inn.asset)) {
            for extra in split.extra {
                let leg = extra.unit == out.asset ? out : inn
                rows.append(row(line, .FEE, rawKind(leg), extra.unit, extra.amount, nil, nil, nil, nil, leg.time, refid + "/fee"))
            }
        }
    }

    private static func row(_ line: Int, _ kind: CsvImportKind, _ rawKind: String, _ coin: String, _ amount: Decimal,
                            _ price: Decimal?, _ quote: String?, _ fee: Decimal?, _ feeUnit: String?,
                            _ time: Int64, _ refid: String) -> CsvImportRow {
        let id: String? = refid.isEmpty ? nil : refid
        return CsvImportRow(
            line: line, kind: kind, rawKind: rawKind, coin: coin, amount: amount, price: price, quote: quote,
            fee: fee, feeUnit: feeUnit, timeMillis: time, externalId: id,
            key: CsvImport.key(format: .KRAKEN, kind: kind, coin: coin, amount: amount, price: price,
                               timeMillis: time, externalId: id))
    }
}
