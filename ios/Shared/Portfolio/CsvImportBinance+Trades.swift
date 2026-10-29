import Foundation

// Handelsverlauf (Teil von `CsvImportBinance`): Paare zerlegen, Ausführungen je Sekunde zu Kauf/Verkauf zusammenfassen
// und mögliche Doppelte zum Transaktionsverlauf — wie `CsvImportBinance.kt` (Android).

extension CsvImportBinance {
    // MARK: - Handelsverlauf

    /// Quote-Währungen der Binance-Paare, längste zuerst («BTCFDUSD» endet auf FDUSD, nicht USD) — wie Android `QUOTES`.
    private static let quotes: [String] = [
        "FDUSD", "USDT", "USDC", "BUSD", "TUSD", "USDP", "BIDR", "IDRT", "EURI", "DAI", "BTC", "ETH", "BNB", "EUR", "GBP",
        "TRY", "BRL", "AUD", "JPY", "PLN", "RON", "CZK", "ZAR", "UAH", "ARS", "MXN", "COP", "USD", "XRP", "TRX", "DOGE",
    ].enumerated().sorted { ($1.element.count, $0.offset) < ($0.element.count, $1.offset) }.map(\.element)

    /// «BTCUSDT» → (BTC, USDT); unbekannte Quote = nil.
    static func splitPair(_ pair: String) -> (base: String, quote: String)? {
        let p = String(pair.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) })
        guard let quote = quotes.first(where: { p.hasSuffix($0) && p.count > $0.count }) else { return nil }
        return (String(p.dropLast(quote.count)), quote)
    }

    /// Gebühr-Coins neben Basis und Quote des Paars (Binance zieht die Gebühr wahlweise in BNB ab) — wie Android `FEE_COINS`.
    private static let feeCoins = ["BNB"]

    /// «0.00100000BTC» → (0.001, BTC); ohne Einheit (früherer Aufbau) → (Zahl, nil); unlesbar = nil. Zuerst die bekannten
    /// Kürzel `known` (Basis und Quote des Paars, bei der Gebühr auch `feeCoins`) am Ende des Feldes, längstes zuerst, mit
    /// lesbarer Zahl davor — so bleiben Ziffern am Anfang des Kürzels beim Kürzel («10.51INCH» → 10.5 1INCH,
    /// «350000.001000SATS» → 350000 1000SATS). Sonst beginnt die Einheit beim ersten Buchstaben nach der Zahl; ein
    /// Exponent gehört zur Zahl («1E-8BNB» → 0.00000001 BNB). Kürzel ohne Buchstaben zählen nicht — wie Android.
    private static func withUnit(_ text: String, _ known: [String]) -> (value: Decimal, unit: String?)? {
        let t = Array(text.trimmingCharacters(in: .whitespaces))
        var seen = Set<String>()
        let units = known.filter { unit in unit.contains(where: { $0.isASCII && $0.isUppercase }) && seen.insert(unit).inserted }
            .enumerated().sorted { ($1.element.count, $0.offset) < ($0.element.count, $1.offset) }.map(\.element)
        for unit in units where t.count > unit.count && String(t.suffix(unit.count)).uppercased() == unit {
            if let number = decimal(String(t.dropLast(unit.count))) { return (number, unit) }
        }
        guard let cut = unitStart(t) else { return decimal(String(t)).map { (value: $0, unit: nil as String?) } }
        guard let number = decimal(String(t[..<cut])) else { return nil }
        return (number, String(t[cut...]).uppercased())
    }

    /// Index des ersten Buchstabens der Einheit (ein Exponent der Zahl übersprungen: «E»/«e» nach einer Ziffer, gefolgt
    /// von Ziffern, mit Vorzeichen davor); keine Einheit = nil. Wie Android `unitStart`.
    private static func unitStart(_ t: [Character]) -> Int? {
        func digit(_ j: Int) -> Bool { j >= 0 && j < t.count && t[j].isASCII && t[j].isNumber }
        guard let first = t.firstIndex(where: { $0.isLetter }) else { return nil }
        guard first >= 1, t[first] == "e" || t[first] == "E", digit(first - 1) else { return first }
        var j = first + 1
        if j < t.count, t[j] == "+" || t[j] == "-" { j += 1 }
        guard digit(j) else { return first }
        while digit(j) { j += 1 }
        return t[j...].firstIndex(where: { $0.isLetter })
    }

    /// Eine Ausführung des Handelsverlaufs (vor dem Zusammenfassen je Sekunde, Paar und Seite).
    private struct Fill {
        let line: Int
        let kind: CsvImportKind
        let rawKind: String
        let base: String
        let quote: String
        let amount: Decimal
        let total: Decimal
        let price: Decimal
        let fee: Decimal?
        let feeUnit: String?
        let time: Int64
        let local: Int64?
    }

    static func trades(_ names: [String], _ data: [CsvImportParser.Record], _ zone: String) -> CsvImportResult {
        let legacy = names.contains("market")
        let iTime = names.firstIndex(of: "dateutc")
        let iPair = names.firstIndex(of: legacy ? "market" : "pair")
        let iSide = names.firstIndex(of: legacy ? "type" : "side")
        let iPrice = names.firstIndex(of: "price")
        let iAmount = names.firstIndex(of: legacy ? "amount" : "executed")
        let iTotal = names.firstIndex(of: legacy ? "total" : "amount")
        let iFee = names.firstIndex(of: "fee")
        let iFeeCoin = names.firstIndex(of: "feecoin")
        var skipped: [CsvImportSkip] = []
        // Ausführungen derselben Sekunde, desselben Paars und derselben Seite: ein Geschäft (Reihenfolge der Datei)
        var groupOrder: [String] = []
        var groups: [String: [Fill]] = [:]
        for rec in data {
            func cell(_ i: Int?) -> String {
                guard let i, i < rec.cells.count else { return "" }
                return rec.cells[i]
            }
            func skip(_ reason: CsvImportSkipReason) { skipped.append(CsvImportSkip(line: rec.line, reason: reason)) }
            let kind: CsvImportKind
            switch CsvImportParser.normalize(cell(iSide)) {
            case "buy": kind = .BUY
            case "sell": kind = .SELL
            default: skip(.UNSUPPORTED_KIND); continue
            }
            let timeText = cell(iTime)
            guard let time = CsvImportParser.parseTime(timeText, zone: zone) else { skip(.BAD_DATE); continue }
            let pair = splitPair(cell(iPair))
            let known = pair.map { [$0.base, $0.quote] } ?? []
            let feeText = cell(iFee)
            let fee = feeText.isEmpty || feeText == "-" ? nil : withUnit(feeText, known + feeCoins)
            guard let executed = withUnit(cell(iAmount), known), let total = withUnit(cell(iTotal), known),
                  let rawPrice = decimal(cell(iPrice)), fee != nil || feeText.isEmpty || feeText == "-" else {
                skip(.BAD_NUMBER)
                continue
            }
            // Einheit an der Zahl (heutiger Aufbau) vor dem zerlegten Paar (früherer Aufbau)
            guard let base = executed.unit ?? pair?.base, let quote = total.unit ?? pair?.quote else { skip(.MISSING_FIELD); continue }
            if CsvImport.isFiat(base) { skip(.NOT_CRYPTO); continue }
            let amount = abs(executed.value)
            let price = abs(rawPrice)
            if amount == 0 || price == 0 { skip(.BAD_NUMBER); continue }
            let feeAmount: Decimal? = fee.map { abs($0.value) }.flatMap { $0 > 0 ? $0 : nil }
            var feeUnit: String?
            if feeAmount != nil {
                let coinColumn = cell(iFeeCoin).uppercased()
                feeUnit = fee?.unit ?? (coinColumn.isEmpty ? nil : coinColumn)
                if feeUnit == nil { skip(.MISSING_FIELD); continue }
            }
            let second = timeText.trimmingCharacters(in: .whitespaces)
            let fill = Fill(line: rec.line, kind: kind, rawKind: cell(iSide), base: base, quote: quote, amount: amount,
                            total: abs(total.value), price: price, fee: feeAmount, feeUnit: feeUnit, time: time,
                            local: CsvImportParser.localTime(timeText))
            let key = "\(second)|\(base)|\(quote)|\(kind.rawValue)"
            if groups[key] == nil { groupOrder.append(key) }
            groups[key, default: []].append(fill)
        }
        let rows = groupOrder.flatMap { order(groups[$0] ?? [], &skipped) }
        return CsvImport.finish(.BINANCE, rows: rows, skipped: skipped, zone: zone, localTimes: true)
    }

    /// Ausführungen einer Order in derselben Sekunde → ein Kauf/Verkauf mit Summen (so fasst sie auch der
    /// Transaktionsverlauf zusammen) — wie Android `order`. Gebühren in verschiedenen Einheiten wie im Transaktionsverlauf:
    /// Fiat- und Coin-Gebühr → Fiat in den Preis (`CsvImport.fiatFeeInPrice`, Preis = (Quote-Summe ± Gebühr) / Menge);
    /// sonst eine am Geschäft, die übrigen als eigene Gebühr-Zeilen (`CsvImport.splitFees`).
    private static func order(_ fills: [Fill], _ skipped: inout [CsvImportSkip]) -> [CsvImportRow] {
        guard let first = fills.first else { return [] }
        var feeUnits: [String] = []
        var feeSums: [String: Decimal] = [:]
        for f in fills {
            guard let unit = f.feeUnit, let fee = f.fee else { continue }
            if feeSums[unit] == nil { feeUnits.append(unit) }
            feeSums[unit, default: 0] += fee
        }
        let buy = first.kind == .BUY
        let amount = fills.reduce(Decimal(0)) { $0 + $1.amount }
        let total = fills.reduce(Decimal(0)) { $0 + $1.total }
        let feeList = feeUnits.map { (unit: $0, amount: feeSums[$0] ?? 0) }
        let folded = CsvImport.fiatFeeInPrice(buy: buy, coin: first.base, fiat: first.quote, total: total, fees: feeList)
        guard let split = CsvImport.splitFees(folded?.rest ?? feeList,
                                              given: buy ? first.quote : first.base,
                                              received: buy ? first.base : first.quote) else {
            for f in fills { skipped.append(CsvImportSkip(line: f.line, reason: .TWO_FEES)) }
            return []
        }
        let fee: Decimal? = split.primary?.amount
        let price: Decimal
        if let folded { price = PortfolioDecimal.divide(folded.total, amount) }
        else { price = fills.count == 1 ? first.price : PortfolioDecimal.divide(total, amount) }
        var draft = CsvImportRow(
            line: first.line, kind: first.kind, rawKind: first.rawKind, coin: first.base, amount: amount, price: price,
            quote: first.quote, fee: fee, feeUnit: fee == nil ? nil : split.primary?.unit, timeMillis: first.time, externalId: nil,
            key: CsvImport.key(format: .BINANCE, kind: first.kind, coin: first.base, amount: amount, price: nil,
                               timeMillis: first.time, externalId: nil, localTime: first.local),
            localTime: first.local)
        draft.feeInPrice = folded != nil
        // Gegen Stablecoin oder Krypto-Coin: Tausch mit dem Quote-Betrag der Datei
        let trade = CsvImportParser.stableSwap(.BINANCE, draft, stableAmount: total) ?? draft
        // Weitere Gebühren: Veräusserung zum Tageskurs (Schlüssel wie im Transaktionsverlauf)
        let extra = split.extra.map { f in
            CsvImportRow(
                line: first.line, kind: .FEE, rawKind: "Fee", coin: f.unit, amount: f.amount, price: nil, quote: nil,
                fee: nil, feeUnit: nil, timeMillis: first.time, externalId: nil,
                key: CsvImport.key(format: .BINANCE, kind: .FEE, coin: f.unit, amount: f.amount, price: nil,
                                   timeMillis: first.time, externalId: nil, localTime: first.local),
                localTime: first.local)
        }
        return [trade] + extra
    }

    /// Zeitraum, in dem ein Binance-Geschäft mit gleicher Art und gleichem Coin als möglicherweise dasselbe gilt.
    private static let nearMillis: Int64 = 5_000

    /// Arten, die Transaktions- und Handelsverlauf beide enthalten.
    private static let tradeKinds: Set<String> = [CsvImportKind.BUY.rawValue, CsvImportKind.SELL.rawValue, CsvImportKind.SWAP.rawValue]

    /// «BINANCE|ART|COIN|L<ms>|…» → (Art, Coin, Kalenderzeit); andere Schlüssel = nil.
    private static func tradeKey(_ key: String) -> (kind: String, coin: String, time: Int64)? {
        let p = key.components(separatedBy: "|")
        guard p.count >= 5, p[0] == CsvImportFormat.BINANCE.rawValue, tradeKinds.contains(p[1]) else { return nil }
        let t = p[3].hasPrefix("L") ? String(p[3].dropFirst()) : p[3]
        guard let time = Int64(t) else { return nil }
        return (p[1], p[2], time)
    }

    /// Neue Binance-Geschäfte (ohne genaue Doppelte), die einem schon gespeicherten Binance-Geschäft gleicher Art und
    /// gleichen Coins bis `nearMillis` nahe kommen, ohne genau gleich zu sein — wie Android `possibleDuplicates`: in die
    /// Prüfliste (`.POSSIBLE_DUPLICATE`) statt ein zweites Mal gezählt.
    static func possibleDuplicates(_ rows: [CsvImportRow], existingKeys: Set<String>) -> [CsvImportRow] {
        let stored = existingKeys.compactMap(tradeKey)
        guard !stored.isEmpty else { return [] }
        return rows.filter { r in
            guard let own = tradeKey(r.key) else { return false }
            return stored.contains { $0.kind == own.kind && $0.coin == own.coin && abs($0.time - own.time) <= nearMillis }
        }
    }
}
