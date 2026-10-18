import Foundation

/// Binance-Exporte → `CsvImportRow` — wie Android `CsvImportBinance` (Regeln und Begründungen dort). Aufbau nur aus den
/// öffentlichen Spaltenbeschreibungen bekannt (synthetische Testdateien in `Tests/Csv/`), echte Beispiele folgen.
///
/// Transaktionsverlauf «User_ID, UTC_Time, Account, Operation, Coin, Change, Remark»: Handel und Gebühr mit gleicher Zeit
/// und gleichem Konto = ein Geschäft (Fiat-Seite → Kauf/Verkauf, sonst Tausch; nur eine Seite → ohne Preis; nur Gebühr →
/// Gebühr-Zeile; weitere Gebühr-Coins → eigene Gebühr-Zeilen; mehrere Coins je Seite → `.AMBIGUOUS_GROUP`), «Deposit»/«Withdraw» →
/// Ein-/Auszahlung, Gutschriften → Belohnung, Verschiebungen zwischen eigenen Konten → `.INTERNAL_MOVE`, Rest → Prüfliste.
/// Handelsverlauf heute «Date(UTC), Pair, Side, Price, Executed, Amount, Fee» (Einheit an der Zahl), früher «Date(UTC),
/// Market, Type, Price, Amount, Total, Fee, Fee Coin»: je Ausführung Kauf/Verkauf, gegen Stablecoin/Krypto ein Tausch.
/// Nie doppelt aus beiden Exporten: Schlüssel ohne Preis mit Kalenderzeit, beide Leser fassen Ausführungen je Sekunde
/// zusammen; Kauf und Verkauf desselben Coins in einer Sekunde nur mit genau zwei Coins getrennt, sonst Prüfliste; nur fast Gleiches hält die Vorschau als
/// möglichen Doppelten zurück (`possibleDuplicates`).
enum CsvImportBinance {

    /// Spalten des Transaktionsverlaufs (normalisiert).
    private static let ledgerColumns: Set<String> = ["utctime", "operation", "coin", "change"]

    /// Spalten des Handelsverlaufs, heutiger Aufbau.
    private static let tradeColumns: Set<String> = ["dateutc", "pair", "side", "price", "executed", "amount", "fee"]

    /// Spalten des Handelsverlaufs, früherer Aufbau.
    private static let legacyTradeColumns: Set<String> = ["dateutc", "market", "type", "price", "amount", "total", "fee", "feecoin"]

    static func matches(_ names: Set<String>) -> Bool {
        ledgerColumns.isSubset(of: names) || tradeColumns.isSubset(of: names) || legacyTradeColumns.isSubset(of: names)
    }

    static func build(names: [String], data: [CsvImportParser.Record], zone: String) -> CsvImportResult {
        ledgerColumns.isSubset(of: Set(names)) ? ledger(names, data, zone) : trades(names, data, zone)
    }

    // MARK: - Transaktionsverlauf

    /// Art einer Zeile des Transaktionsverlaufs.
    private enum Op { case trade, fee, deposit, withdrawal, reward, internalMove, unknown }

    private static let feeOps: Set<String> = ["fee", "transactionfee"]
    private static let tradeOps: Set<String> = [
        "buy", "sell", "transactionbuy", "transactionspend", "transactionsold", "transactionrevenue", "transactionrelated",
        "binanceconvert", "largeotctrading", "autoinvesttransaction", "p2ptrading", "buycrypto", "smallassetsexchangebnb",
    ]
    private static let depositOps: Set<String> = ["deposit", "fiatdeposit"]
    private static let withdrawalOps: Set<String> = ["withdraw", "withdrawal", "fiatwithdraw", "fiatwithdrawal"]

    /// Verschiebungen zwischen eigenen Konten mit genauem Namen: «ETH 2.0 Staking» (ETH → BETH) und ihre Rücknahme. Genau,
    /// nicht als Teilwort — sonst fiele «ETH 2.0 Staking Rewards» (Belohnung) als Verschiebung weg. Wie Android.
    private static let internalOps: Set<String> = ["eth20staking", "eth20stakingwithdrawals"]

    /// Teilwörter einer Verschiebung zwischen eigenen Konten (vor den Belohnungen: «Staking Purchase» ist keine).
    private static let internalParts = ["transferbetween", "transferfunds", "subscription", "redemption", "purchase"]

    /// Teilwörter einer Gutschrift (Zins, Staking, Launchpool, Airdrop, Empfehlung, Cashback, Mining).
    private static let rewardParts = ["interest", "reward", "airdrop", "distribution", "cashback", "commission", "kickback",
                                      "launchpool", "mining", "bonus", "rebate"]

    private static func op(_ normalized: String) -> Op {
        if feeOps.contains(normalized) { return .fee }
        if tradeOps.contains(normalized) { return .trade }
        if depositOps.contains(normalized) { return .deposit }
        if withdrawalOps.contains(normalized) { return .withdrawal }
        if internalOps.contains(normalized) { return .internalMove }
        if internalParts.contains(where: { normalized.contains($0) }) { return .internalMove }
        if rewardParts.contains(where: { normalized.contains($0) }) { return .reward }
        return .unknown
    }

    private struct Leg {
        let line: Int
        let op: Op
        let rawKind: String
        let coin: String
        let change: Decimal
        let time: Int64
        let local: Int64?
    }

    private static func ledger(_ names: [String], _ data: [CsvImportParser.Record], _ zone: String) -> CsvImportResult {
        let iTime = names.firstIndex(of: "utctime")
        let iAccount = names.firstIndex(of: "account")
        let iOp = names.firstIndex(of: "operation")
        let iCoin = names.firstIndex(of: "coin")
        let iChange = names.firstIndex(of: "change")
        var rows: [CsvImportRow] = []
        var skipped: [CsvImportSkip] = []
        // Handel und Gebühr: je Zeit und Konto eine Gruppe (Reihenfolge der Datei)
        var groupOrder: [String] = []
        var groups: [String: [Leg]] = [:]
        for rec in data {
            func cell(_ i: Int?) -> String {
                guard let i, i < rec.cells.count else { return "" }
                return rec.cells[i]
            }
            let rawKind = cell(iOp)
            let coin = cell(iCoin).uppercased()
            let change = decimal(cell(iChange))
            let timeText = cell(iTime)
            let time = CsvImportParser.parseTime(timeText, zone: zone)
            if rawKind.isEmpty || coin.isEmpty {
                skipped.append(CsvImportSkip(line: rec.line, reason: .MISSING_FIELD))
            } else if change == nil {
                skipped.append(CsvImportSkip(line: rec.line, reason: .BAD_NUMBER))
            } else if time == nil {
                skipped.append(CsvImportSkip(line: rec.line, reason: .BAD_DATE))
            } else if let change, let time {
                let leg = Leg(line: rec.line, op: op(CsvImportParser.normalize(rawKind)), rawKind: rawKind, coin: coin,
                              change: change, time: time, local: CsvImportParser.localTime(timeText))
                switch leg.op {
                case .trade, .fee:
                    let key = timeText.trimmingCharacters(in: .whitespaces) + "|" + cell(iAccount).trimmingCharacters(in: .whitespaces)
                    if groups[key] == nil { groupOrder.append(key) }
                    groups[key, default: []].append(leg)
                case .deposit, .withdrawal: move(leg, &rows, &skipped)
                case .reward: reward(leg, &rows, &skipped)
                case .internalMove: skipped.append(CsvImportSkip(line: leg.line, reason: .INTERNAL_MOVE))
                case .unknown: skipped.append(CsvImportSkip(line: leg.line, reason: .UNSUPPORTED_KIND))
                }
            }
        }
        for key in groupOrder { trade(groups[key] ?? [], &rows, &skipped) }
        return CsvImport.finish(.BINANCE, rows: rows, skipped: skipped, zone: zone, localTimes: true)
    }

    private static func move(_ leg: Leg, _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        let amount = abs(leg.change)
        if CsvImport.isFiat(leg.coin) {
            skipped.append(CsvImportSkip(line: leg.line, reason: .NOT_CRYPTO))
        } else if amount == 0 {
            skipped.append(CsvImportSkip(line: leg.line, reason: .BAD_NUMBER))
        } else {
            let kind: CsvImportKind = leg.op == .deposit ? .DEPOSIT : .WITHDRAWAL
            rows.append(row(leg.line, kind, leg.rawKind, leg.coin, amount, nil, nil, nil, nil, leg))
        }
    }

    private static func reward(_ leg: Leg, _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        if CsvImport.isFiat(leg.coin) {
            skipped.append(CsvImportSkip(line: leg.line, reason: .NOT_CRYPTO))
        } else if leg.change <= 0 {
            // Abgang mit Belohnungswort (z. B. Rückbuchung) ist keine Gutschrift: Prüfliste
            skipped.append(CsvImportSkip(line: leg.line, reason: .UNSUPPORTED_KIND))
        } else {
            rows.append(row(leg.line, .REWARD, leg.rawKind, leg.coin, leg.change, nil, nil, nil, nil, leg))
        }
    }

    /// Ein Geschäft aus den Zeilen einer Gruppe (gleiche Zeit, gleiches Konto) — wie Android `trade`. Kauf UND Verkauf
    /// desselben Coins in der Sekunde: eindeutig nur mit genau zwei Coins, die beide in beide Richtungen gehen (A → B und
    /// B → A; Gebühren nur in A oder B, je am Geschäft, das diesen Coin erhält) → zwei Geschäfte; sonst wie mehrere Coins
    /// je Seite `.AMBIGUOUS_GROUP`.
    private static func trade(_ group: [Leg], _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        guard !group.isEmpty else { return }
        func skipAll(_ reason: CsvImportSkipReason) {
            for leg in group { skipped.append(CsvImportSkip(line: leg.line, reason: reason)) }
        }
        let tradeLegs = group.filter { $0.op == .trade }
        let feeLegs = group.filter { $0.op == .fee }
        // Summen je Coin in Reihenfolge des ersten Auftretens (wie `LinkedHashMap`)
        var feeCoins: [String] = []
        var feeSums: [String: Decimal] = [:]
        for leg in feeLegs {
            if feeSums[leg.coin] == nil { feeCoins.append(leg.coin) }
            feeSums[leg.coin, default: 0] += leg.change
        }
        let fees: [(unit: String, amount: Decimal)] = feeCoins.compactMap { c in
            let v = feeSums[c] ?? 0
            return v == 0 ? nil : (unit: c, amount: abs(v))
        }
        var coins: [String] = []
        for leg in tradeLegs where !coins.contains(leg.coin) { coins.append(leg.coin) }
        let bothWays = coins.filter { c in
            tradeLegs.contains { $0.coin == c && $0.change > 0 } && tradeLegs.contains { $0.coin == c && $0.change < 0 }
        }
        if !bothWays.isEmpty {
            guard coins.count == 2, bothWays.count == 2, fees.allSatisfy({ coins.contains($0.unit) }) else {
                skipAll(.AMBIGUOUS_GROUP)
                return
            }
            for (out, inn) in [(coins[0], coins[1]), (coins[1], coins[0])] {
                let outLegs = tradeLegs.filter { $0.coin == out && $0.change < 0 }
                let inLegs = tradeLegs.filter { $0.coin == inn && $0.change > 0 }
                let outSum = abs(outLegs.reduce(Decimal(0)) { $0 + $1.change })
                let inSum = inLegs.reduce(Decimal(0)) { $0 + $1.change }
                let ownFeeLegs = feeLegs.filter { $0.coin == inn }
                emit((outLegs + inLegs + ownFeeLegs).sorted { $0.line < $1.line }, (coin: out, amount: outSum), (coin: inn, amount: inSum),
                     fees.filter { $0.unit == inn }, &rows, &skipped)
            }
            return
        }
        var netCoins: [String] = []
        var nets: [String: Decimal] = [:]
        for leg in tradeLegs {
            if nets[leg.coin] == nil { netCoins.append(leg.coin) }
            nets[leg.coin, default: 0] += leg.change
        }
        let given = netCoins.filter { (nets[$0] ?? 0) < 0 }
        let received = netCoins.filter { (nets[$0] ?? 0) > 0 }
        if given.count > 1 || received.count > 1 { skipAll(.AMBIGUOUS_GROUP); return }
        emit(group, given.first.map { (coin: $0, amount: abs(nets[$0] ?? 0)) }, received.first.map { (coin: $0, amount: nets[$0] ?? 0) },
             fees, &rows, &skipped)
    }

    /// Zeilen eines Geschäfts aus `legs` — wie Android `emit`: höchstens ein Coin ab (`given`) und einer zu (`received`),
    /// Gebühren in mehreren Einheiten: eine am Geschäft, die übrigen als eigene Gebühr-Zeilen (`CsvImport.splitFees`).
    private static func emit(_ legs: [Leg], _ given: (coin: String, amount: Decimal)?, _ received: (coin: String, amount: Decimal)?,
                             _ fees: [(unit: String, amount: Decimal)],
                             _ rows: inout [CsvImportRow], _ skipped: inout [CsvImportSkip]) {
        guard let first = legs.first else { return }
        func skipAll(_ reason: CsvImportSkipReason) {
            for leg in legs { skipped.append(CsvImportSkip(line: leg.line, reason: reason)) }
        }
        let line = legs.map(\.line).min() ?? first.line
        // Art wie in der Datei: Zeile des gekauften Coins beim Kauf, sonst des abgegebenen
        func rawKind(_ coin: String) -> String { legs.first { $0.op == .trade && $0.coin == coin }?.rawKind ?? first.rawKind }
        func feeRow(_ unit: String, _ amount: Decimal, _ at: Int) -> CsvImportRow {
            let kind = legs.first { $0.op == .fee && $0.coin == unit }?.rawKind ?? first.rawKind
            return row(at, .FEE, kind, unit, amount, nil, nil, nil, nil, first)
        }
        if given == nil && received == nil {
            // Nur Gebühr: Veräusserung der Gebühr-Coins zum Tageskurs (je Einheit eine Zeile; Fiat ist kein Krypto)
            if fees.isEmpty { skipAll(.BAD_NUMBER); return }
            if fees.allSatisfy({ CsvImport.isFiat($0.unit) }) { skipAll(.NOT_CRYPTO); return }
            if fees.count == 1 { rows.append(feeRow(fees[0].unit, fees[0].amount, line)); return }
            for f in fees {
                let own = legs.filter { $0.coin == f.unit }
                if CsvImport.isFiat(f.unit) {
                    for leg in own { skipped.append(CsvImportSkip(line: leg.line, reason: .NOT_CRYPTO)) }
                } else {
                    rows.append(feeRow(f.unit, f.amount, own.map(\.line).min() ?? line))
                }
            }
            return
        }
        guard let split = CsvImport.splitFees(fees, given: given?.coin, received: received?.coin) else { skipAll(.TWO_FEES); return }
        if let g = given, let r = received, CsvImport.isFiat(g.coin) && CsvImport.isFiat(r.coin) { skipAll(.NOT_CRYPTO); return }
        let feeAmount: Decimal? = split.primary?.amount
        let feeUnit: String? = split.primary?.unit
        if let g = given, let r = received {
            let (out, outAmount) = (g.coin, g.amount)
            let (inn, inAmount) = (r.coin, r.amount)
            if CsvImport.isFiat(out) {
                rows.append(row(line, .BUY, rawKind(inn), inn, inAmount, CsvImport.divide(outAmount, inAmount), out,
                                feeAmount, feeUnit, first))
            } else if CsvImport.isFiat(inn) {
                rows.append(row(line, .SELL, rawKind(out), out, outAmount, CsvImport.divide(inAmount, outAmount), inn,
                                feeAmount, feeUnit, first))
            } else {
                let value = CsvImport.stableSwapValue(coin: out, amount: outAmount, toCoin: inn, toAmount: inAmount)
                var swap = row(line, .SWAP, rawKind(out), out, outAmount, nil, value.quote, feeAmount, feeUnit, first)
                swap.toCoin = inn
                swap.toAmount = inAmount
                swap.swapValue = value.value
                swap.stableSwap = CsvImport.isStable(out) || CsvImport.isStable(inn)
                rows.append(swap)
            }
        } else if let side = received ?? given {
            // Nur eine Seite (Kartenkauf, P2P): Kauf bzw. Verkauf ohne Preis
            if CsvImport.isFiat(side.coin) { skipAll(.NOT_CRYPTO); return }
            let kind: CsvImportKind = received == nil ? .SELL : .BUY
            rows.append(row(line, kind, rawKind(side.coin), side.coin, side.amount, nil, nil, feeAmount, feeUnit, first))
        }
        // Weitere Gebühren (andere Einheit): eigene Gebühr-Zeilen statt das Geschäft zu überspringen
        for f in split.extra { rows.append(feeRow(f.unit, f.amount, line)) }
    }

    /// Zeile mit Schlüssel ohne Preis (siehe Kopf), Zeit von `leg`.
    private static func row(_ line: Int, _ kind: CsvImportKind, _ rawKind: String, _ coin: String, _ amount: Decimal,
                            _ price: Decimal?, _ quote: String?, _ fee: Decimal?, _ feeUnit: String?, _ leg: Leg) -> CsvImportRow {
        let validFee: Decimal? = fee.flatMap { $0 > 0 ? $0 : nil }
        return CsvImportRow(
            line: line, kind: kind, rawKind: rawKind, coin: coin, amount: amount, price: price, quote: quote,
            fee: validFee, feeUnit: validFee == nil ? nil : feeUnit, timeMillis: leg.time, externalId: nil,
            key: CsvImport.key(format: .BINANCE, kind: kind, coin: coin, amount: amount, price: nil, timeMillis: leg.time,
                               externalId: nil, localTime: leg.local),
            localTime: leg.local)
    }

    // MARK: - Zahlen

    private static let posix = Locale(identifier: "en_US_POSIX")

    /// «1E-8», «-2.5e3»: Ziffern, höchstens ein Punkt, dann e/E, Vorzeichen, 1–3 Ziffern (wie Android `EXPONENT`).
    private static func isExponent(_ t: String) -> Bool {
        let parts = t.lowercased().split(separator: "e", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        var mantissa = Substring(parts[0])
        if mantissa.hasPrefix("-") { mantissa = mantissa.dropFirst() }
        let pieces = mantissa.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...2).contains(pieces.count), pieces.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isASCII && $0.isNumber } }) else {
            return false
        }
        var exponent = Substring(parts[1])
        if exponent.hasPrefix("-") || exponent.hasPrefix("+") { exponent = exponent.dropFirst() }
        return (1...3).contains(exponent.count) && exponent.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// Zahl mit Punkt als Dezimalzeichen; auch «1E-8» (Binance schreibt sehr kleine Beträge so).
    static func decimal(_ text: String) -> Decimal? {
        let t = text.trimmingCharacters(in: .whitespaces)
        if isExponent(t) { return Decimal(string: t, locale: posix) }
        return CsvImportParser.parseNumber(t, hint: ".")
    }
}
