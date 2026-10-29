import Foundation

/// Coinbase «Transaction report» → `CsvImportRow` — wie Android `CsvImportCoinbase` (Regeln und Begründungen dort).
/// Aufbau nur aus der öffentlichen Beschreibung bekannt (synthetische Testdateien in `Tests/Csv/`), echte Beispiele folgen.
///
/// Vorspann (Hinweis, «Transactions», «User, …») überspringt die Kopfzeilensuche. Spalten heute «ID, Timestamp, Transaction
/// Type, Asset, Quantity Transacted, Price Currency, Price at Transaction, Subtotal, Total (…), Fees and/or Spread, Notes»,
/// früher ohne «ID» und mit «Spot Price Currency»/«Spot Price at Transaction»/«Fees». Kauf/Verkauf: Preis = Subtotal /
/// Menge, Gebühr in der Preiswährung (gegen USDC ein Tausch); «Convert» → Tausch mit der Gegenseite aus «Notes»; «Send»/
/// «Receive» → Aus-/Einzahlung; Gutschriften → Belohnung; Verschiebungen zu Coinbase Pro bzw. ins Staking → `.INTERNAL_MOVE`;
/// ETH2 zählt als ETH; Rest → Prüfliste.
enum CsvImportCoinbase {

    private static let required: Set<String> = ["timestamp", "transactiontype", "asset", "quantitytransacted"]

    static func matches(_ names: Set<String>) -> Bool { required.isSubset(of: names) }

    private static let buyTypes: Set<String> = ["buy", "advancedtradebuy", "recurringbuy"]
    private static let sellTypes: Set<String> = ["sell", "advancedtradesell"]
    private static let withdrawalTypes: Set<String> = ["send", "withdrawal"]
    private static let depositTypes: Set<String> = ["receive", "deposit"]
    private static let internalTypes: Set<String> = [
        "prodeposit", "prowithdrawal", "exchangedeposit", "exchangewithdrawal", "retailstakingtransfer",
        "retailunstakingtransfer", "retaileth2deprecation",
    ]

    /// Teilwörter einer Gutschrift («Rewards Income», «Coinbase Earn», «Subscription Rebate» …).
    private static let rewardParts = ["reward", "income", "earn", "rebate"]

    /// Art aus der normalisierten Spalte «Transaction Type»; nil = Verschiebung bzw. unbekannt (Grund in `skipReason`).
    private static func kind(_ type: String) -> CsvImportKind? {
        if buyTypes.contains(type) { return .BUY }
        if sellTypes.contains(type) { return .SELL }
        if type == "convert" { return .SWAP }
        if withdrawalTypes.contains(type) { return .WITHDRAWAL }
        if depositTypes.contains(type) { return .DEPOSIT }
        if internalTypes.contains(type) { return nil }
        if rewardParts.contains(where: { type.contains($0) }) { return .REWARD }
        return nil
    }

    private static func skipReason(_ type: String) -> CsvImportSkipReason {
        internalTypes.contains(type) ? .INTERNAL_MOVE : .UNSUPPORTED_KIND
    }

    /// «Converted 0.5 ETH to 0.03 BTC» (Gross/Klein egal, Tausender mit Komma) — wie Android `CONVERTED`.
    private static let converted = try? NSRegularExpression(
        pattern: "converted\\s+([0-9.,]+)\\s+([a-z0-9]+)\\s+to\\s+([0-9.,]+)\\s+([a-z0-9]+)", options: [.caseInsensitive])

    private struct Skip: Error {
        let reason: CsvImportSkipReason
    }

    static func build(names: [String], data: [CsvImportParser.Record], zone: String) -> CsvImportResult {
        func col(_ candidates: String...) -> Int? { candidates.lazy.compactMap { names.firstIndex(of: $0) }.first }
        let iId = col("id")
        let iTime = col("timestamp")
        let iType = col("transactiontype")
        let iAsset = col("asset")
        let iQuantity = col("quantitytransacted")
        let iCurrency = col("pricecurrency", "spotpricecurrency")
        let iPrice = col("priceattransaction", "spotpriceattransaction")
        let iSubtotal = col("subtotal")
        let iFee = col("feesandorspread", "fees")
        let iNotes = col("notes")
        var rows: [CsvImportRow] = []
        var skipped: [CsvImportSkip] = []
        var localTimes = false
        for rec in data {
            func cell(_ i: Int?) -> String {
                guard let i, i < rec.cells.count else { return "" }
                return rec.cells[i]
            }
            func positive(_ value: Decimal?) -> Decimal? { value.map { abs($0) }.flatMap { $0 > 0 ? $0 : nil } }
            do {
                let type = CsvImportParser.normalize(cell(iType))
                guard let kind = Self.kind(type) else { throw Skip(reason: skipReason(type)) }
                let coin = asset(cell(iAsset))
                if coin.isEmpty { throw Skip(reason: .MISSING_FIELD) }
                if CsvImport.isFiat(coin) { throw Skip(reason: .NOT_CRYPTO) }
                let timeText = cell(iTime)
                guard let time = CsvImportParser.parseTime(timeText, zone: zone) else { throw Skip(reason: .BAD_DATE) }
                let local = CsvImportParser.localTime(timeText)
                if local != nil { localTimes = true }
                if cell(iQuantity).isEmpty { throw Skip(reason: .MISSING_FIELD) }
                guard let quantity = money(cell(iQuantity)) else { throw Skip(reason: .BAD_NUMBER) }
                let amount = abs(quantity)
                if amount == 0 { throw Skip(reason: .BAD_NUMBER) }
                let currency = cell(iCurrency).uppercased()
                let quote: String? = currency.isEmpty ? nil : currency
                let subtotal = positive(money(cell(iSubtotal)))
                let spot = positive(money(cell(iPrice)))
                // Gebühr und Spread in der Preiswährung; ohne Währung nicht zuordenbar — überspringen statt still weglassen
                let fee = positive(money(cell(iFee)))
                if fee != nil && quote == nil { throw Skip(reason: .MISSING_FIELD) }
                let idText = cell(iId)
                let externalId: String? = idText.isEmpty ? nil : idText
                let priced = kind == .BUY || kind == .SELL || kind == .REWARD
                var price: Decimal?
                if priced { price = subtotal.map { PortfolioDecimal.divide($0, amount) } ?? spot }
                if price != nil && quote == nil { throw Skip(reason: .MISSING_FIELD) }
                var toCoin: String?
                var toAmount: Decimal?
                var swapValue: Decimal?
                var rowQuote: String? = (price != nil || fee != nil) ? quote : nil
                if kind == .SWAP {
                    guard let g = groups(converted, cell(iNotes)) else { throw Skip(reason: .MISSING_FIELD) }
                    let to = asset(g[4])
                    guard let received = positive(CsvImportParser.parseNumber(g[3], hint: ".")) else { throw Skip(reason: .BAD_NUMBER) }
                    if to == coin { throw Skip(reason: .MISSING_FIELD) }
                    if let q = quote, CsvImport.money.contains(q) { swapValue = subtotal ?? spot.map { PortfolioDecimal.multiply($0, amount) } }
                    toCoin = to
                    toAmount = received
                    rowQuote = (swapValue != nil || fee != nil) ? quote : nil
                }
                let draft = CsvImportRow(
                    line: rec.line, kind: kind, rawKind: cell(iType), coin: coin, amount: amount, price: price,
                    // Preiswährung nur, wo sie zählt (Preis, Wert oder Gebühr); bei Send/Receive ohne Gebühr bedeutungslos
                    quote: rowQuote, fee: fee, feeUnit: fee == nil ? nil : quote, timeMillis: time, externalId: externalId,
                    key: CsvImport.key(format: .COINBASE, kind: kind, coin: coin, amount: amount, price: price,
                                       timeMillis: time, externalId: externalId, localTime: local),
                    toCoin: toCoin, toAmount: toAmount, swapValue: swapValue, localTime: local)
                rows.append(CsvImportParser.stableSwap(.COINBASE, draft, stableAmount: subtotal) ?? draft)
            } catch let s as Skip {
                skipped.append(CsvImportSkip(line: rec.line, reason: s.reason))
            } catch {
                skipped.append(CsvImportSkip(line: rec.line, reason: .MISSING_FIELD))
            }
        }
        return CsvImport.finish(.COINBASE, rows: rows, skipped: skipped, zone: zone, localTimes: localTimes)
    }

    /// Coinbase-Kürzel vereinheitlichen: ETH2 (gestaktes ETH bis 2023) zählt als ETH.
    private static func asset(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespaces).uppercased()
        return t == "ETH2" ? "ETH" : t
    }

    /// Betrag ohne Währungszeichen («€40,000.00», «-$12.50», «CHF 3.00», «EUR 3.00»); Punkt = Dezimalzeichen; leer/unlesbar
    /// = nil. Wissenschaftliche Schreibweise bleibt (`exponentAt`), wie Excel grosse und kleine Mengen speichert
    /// («1.23457E+11», «1.5E-05»): `CsvImportParser.parseNumber` liest sie wie in jedem anderen Format. Ziffern nur 0–9
    /// (andere Schriften fallen weg wie Währungszeichen) — wie Android.
    private static func money(_ text: String) -> Decimal? {
        let chars = Array(text)
        var t = ""
        for (i, c) in chars.enumerated() {
            let kept = (c.isASCII && c.isNumber) || ".,-−+".contains(c)
            if kept || ((c == "e" || c == "E") && exponentAt(chars, i)) { t.append(c) }
        }
        return t.isEmpty ? nil : CsvImportParser.parseNumber(t, hint: ".")
    }

    /// «E»/«e» an `i` ist ein Exponent: direkt nach einer Ziffer und vor einer Ziffer (bzw. Vorzeichen und Ziffer) — ein
    /// Währungskürzel («EUR 3.00», «3.00 EUR») nie. Wie Android `exponentAt`.
    private static func exponentAt(_ chars: [Character], _ i: Int) -> Bool {
        func digit(_ j: Int) -> Bool { j >= 0 && j < chars.count && chars[j].isASCII && chars[j].isNumber }
        let signed = i + 1 < chars.count && "+-−".contains(chars[i + 1])
        return digit(i - 1) && digit(signed ? i + 2 : i + 1)
    }

    /// Gruppen des ersten Treffers ("" für nicht beteiligte); nil = kein Treffer.
    private static func groups(_ regex: NSRegularExpression?, _ text: String) -> [String]? {
        guard let regex else { return nil }
        let ns = text as NSString
        guard let m = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }
}
