import Foundation

// Zeilen der Relai-/Swissquote-Leser (Teil von `CsvImportParser`): Ergebnis bauen, Stablecoin-Kauf/-Verkauf als
// Tausch, Tausch-Seiten paaren und eine Zeile lesen — wie `CsvImportParser.kt` (Android).

extension CsvImportParser {
    // MARK: - Zeilen

    private struct Skip: Error {
        let reason: CsvImportSkipReason
    }

    static func build(_ format: CsvImportFormat, _ h: Header, _ data: [Record], zone: String,
                              chosenOrder: CsvDateOrder?, chosenMark: Character?) -> CsvImportResult {
        switch format {
        case .KRAKEN: return CsvImportKraken.build(names: h.names, data: data, zone: zone)
        case .BINANCE: return CsvImportBinance.build(names: h.names, data: data, zone: zone)
        case .COINBASE: return CsvImportCoinbase.build(names: h.names, data: data, zone: zone)
        case .BITPANDA: return CsvImportBitpanda.build(names: h.names, data: data, zone: zone)
        case .RELAI, .SWISSQUOTE, .GENERIC, .UNKNOWN: break
        }
        let dateIndex: Int? = h.index[.date]
        // Tag/Monat je Datei: Swissquote immer Tag zuerst; sonst aus den Daten, nur mehrdeutige → Auswahl der Vorschau
        var detected: CsvDateOrder? = .DAY_FIRST
        if format != .SWISSQUOTE, let di = dateIndex {
            detected = detectDateOrder(data.map { di < $0.cells.count ? $0.cells[di] : "" })
        }
        let ambiguous = detected == nil
        let order: CsvDateOrder = detected ?? chosenOrder ?? .DAY_FIRST
        let monthFirst = order == .MONTH_FIRST
        let numericRoles: [Role] = [.amount, .amountSats, .price, .fiatAmount, .fee]
        var numericCells: [String] = []
        for r in data {
            for role in numericRoles {
                if let i = h.index[role], i < r.cells.count { numericCells.append(r.cells[i]) }
            }
        }
        let detectedMark = decimalHint(numericCells)
        // Datei verrät kein Dezimalzeichen und hat «42,000»: Vorschau fragt; ohne Wahl das Zeichen der ersten mehrdeutigen
        // Zahl = Dezimalzeichen (gilt dann für die ganze Datei) — wie Android
        let decimalSample: String? = detectedMark == nil ? ambiguousNumber(numericCells) : nil
        var hint: Character? = detectedMark
        if hint == nil, let sample = decimalSample {
            if let m = chosenMark, m == "." || m == "," { hint = m } else { hint = defaultMark(sample) }
        }
        var parsed: [(row: CsvImportRow, sign: Int)] = []
        var skipped: [CsvImportSkip] = []
        for rec in data {
            do {
                parsed.append(try row(format, h, rec, hint: hint, zone: zone, monthFirst: monthFirst))
            } catch let s as Skip {
                skipped.append(CsvImportSkip(line: rec.line, reason: s.reason))
            } catch {
                skipped.append(CsvImportSkip(line: rec.line, reason: .MISSING_FIELD))
            }
        }
        // Gebühr ≥ erhaltene Menge: übersprungen mit Grund (wie das Blatt nicht speicherbar)
        var drafts: [CsvImportRow] = pairSwapLegs(format, parsed, &skipped).filter { r -> Bool in
            let consumes = CsvImport.feeConsumesAmount(r)
            if consumes { skipped.append(CsvImportSkip(line: r.line, reason: .FEE_EXCEEDS_AMOUNT)) }
            return !consumes
        }
        // stabil nach Zeile (wie Android `sortBy`)
        skipped = skipped.enumerated().sorted { ($0.element.line, $0.offset) < ($1.element.line, $1.offset) }.map(\.element)
        let keys = CsvImport.withOccurrence(drafts.map(\.key))
        for i in drafts.indices { drafts[i].key = keys[i] }
        // Zählt die Zeitzone? Nur wenn eine lesbare Zeit ohne Versatz vorkommt
        var localTimes = false
        if let di = dateIndex {
            localTimes = data.contains { r in
                let t: String = di < r.cells.count ? r.cells[di] : ""
                return !hasOffset(t) && parseTime(t, zone: zone, monthFirst: monthFirst) != nil
            }
        }
        return CsvImportResult(format: format, rows: drafts, skipped: skipped, zone: zone, localTimes: localTimes,
                               dateOrder: order, dateOrderAmbiguous: ambiguous, decimalSample: decimalSample,
                               decimalMark: decimalSample == nil ? nil : hint)
    }

    /// Ohne Wahl gilt das einzige Zeichen einer Zahl als Dezimalzeichen (`parseNumber` ohne Hinweis).
    private static func defaultMark(_ sample: String) -> Character { sample.contains(",") ? "," : "." }

    /// Wert von `sample` mit Dezimalzeichen `mark` («.» oder «,»), für die Auswahl der Vorschau; nil = nicht lesbar.
    static func sampleValue(_ sample: String, mark: Character) -> Decimal? { parseNumber(sample, hint: mark) }

    /// Kauf/Verkauf gegen einen Stablecoin → Tausch — wie Android `stableSwap`: Verkauf X gegen S = Tausch X → S, Kauf X
    /// mit S = Tausch S → X; S-Menge = `stableAmount` (Fiat-/Quote-Betrag der Datei), sonst Preis × Menge; Wert nur bei
    /// USDT. nil = bleibt wie `r` (kein Stablecoin, ohne Preis, Coin = Stablecoin der Preiswährung).
    /// Ebenso gegen einen Krypto-Coin (`CsvImport.isCryptoQuote`, Paar «ETH/BTC»): Wert unbekannt, ohne Stablecoin-Hinweis.
    static func stableSwap(_ format: CsvImportFormat, _ r: CsvImportRow, stableAmount: Decimal?) -> CsvImportRow? {
        guard r.kind == .BUY || r.kind == .SELL, let price = r.price, let stable = r.quote,
              CsvImport.isStable(stable) || CsvImport.isCryptoQuote(stable), stable != r.coin else { return nil }
        let sAmount = stableAmount ?? price * r.amount
        let sell = r.kind == .SELL
        let coin = sell ? r.coin : stable
        let amount = sell ? r.amount : sAmount
        let toCoin = sell ? stable : r.coin
        let toAmount = sell ? sAmount : r.amount
        let value = CsvImport.stableSwapValue(coin: coin, amount: amount, toCoin: toCoin, toAmount: toAmount)
        return CsvImportRow(
            line: r.line,
            kind: .SWAP,
            rawKind: r.rawKind,
            coin: coin,
            amount: amount,
            price: nil,
            quote: value.quote,
            fee: r.fee,
            feeUnit: r.feeUnit,
            timeMillis: r.timeMillis,
            externalId: r.externalId,
            key: CsvImport.key(format: format, kind: .SWAP, coin: coin, amount: amount, price: nil,
                               timeMillis: r.timeMillis, externalId: r.externalId, localTime: r.localTime),
            toCoin: toCoin,
            toAmount: toAmount,
            swapValue: value.value,
            localTime: r.localTime,
            stableSwap: CsvImport.isStable(stable)
        )
    }

    /// Tausch-Seiten (Tausch-Zeile ohne erhaltene Seite) paarweise zu einer Zeile — wie Android `pairSwapLegs`:
    /// zwei aufeinanderfolgende Seiten mit gleicher Kennung (fehlt eine: gleicher Zeit) und verschiedenen Coins;
    /// abgegeben = negative Menge, sonst Verkaufs-/Ausgangswort in der Art; sonst übersprungen.
    private static func pairSwapLegs(_ format: CsvImportFormat, _ parsed: [(row: CsvImportRow, sign: Int)],
                                     _ skipped: inout [CsvImportSkip]) -> [CsvImportRow] {
        func isLeg(_ r: CsvImportRow) -> Bool { r.kind == .SWAP && r.toCoin == nil }
        func outWord(_ r: CsvImportRow) -> Bool {
            let t = normalize(r.rawKind)
            return ["sell", "verkauf", "vente", "out", "from", "ausgang", "debit", "belastung", "abgang"].contains { t.contains($0) }
        }
        var out: [CsvImportRow] = []
        var i = 0
        while i < parsed.count {
            let a = parsed[i].row
            let signA = parsed[i].sign
            if !isLeg(a) { out.append(a); i += 1; continue }
            guard i + 1 < parsed.count else {
                skipped.append(CsvImportSkip(line: a.line, reason: .MISSING_FIELD))
                i += 1
                continue
            }
            let b = parsed[i + 1].row
            let signB = parsed[i + 1].sign
            let sameId: Bool
            if let ia = a.externalId, let ib = b.externalId { sameId = ia == ib } else { sameId = a.timeMillis == b.timeMillis }
            if !(isLeg(b) && a.coin != b.coin && sameId) {
                skipped.append(CsvImportSkip(line: a.line, reason: .MISSING_FIELD))
                i += 1
                continue
            }
            let aGiven: Bool?
            if signA < 0 && signB > 0 { aGiven = true }
            else if signB < 0 && signA > 0 { aGiven = false }
            else if outWord(a) && !outWord(b) { aGiven = true }
            else if outWord(b) && !outWord(a) { aGiven = false }
            else { aGiven = nil }
            guard let isAGiven = aGiven else {
                skipped.append(CsvImportSkip(line: a.line, reason: .MISSING_FIELD))
                skipped.append(CsvImportSkip(line: b.line, reason: .MISSING_FIELD))
                i += 2
                continue
            }
            let given = isAGiven ? a : b
            let received = isAGiven ? b : a
            // Wert: abgegebene Seite zuerst; Gebühr: abgegebene Seite zuerst
            let valueRow = given.swapValue != nil ? given : received
            // Beide Seiten mit Gebühr: gleiche Einheit → zusammenzählen; verschiedene → nicht still eine verwerfen,
            // beide Zeilen überspringen (Tausch von Hand erfassen)
            guard let fees = CsvImport.swapFee(givenFee: given.fee, givenUnit: given.feeUnit,
                                               receivedFee: received.fee, receivedUnit: received.feeUnit) else {
                skipped.append(CsvImportSkip(line: a.line, reason: .TWO_FEES))
                skipped.append(CsvImportSkip(line: b.line, reason: .TWO_FEES))
                i += 2
                continue
            }
            let externalId = given.externalId ?? received.externalId
            out.append(CsvImportRow(
                line: min(a.line, b.line),
                kind: .SWAP,
                rawKind: given.rawKind,
                coin: given.coin,
                amount: given.amount,
                price: nil,
                quote: valueRow.swapValue != nil ? valueRow.quote : nil,
                fee: fees.fee,
                feeUnit: fees.unit,
                timeMillis: given.timeMillis,
                externalId: externalId,
                key: CsvImport.key(format: format, kind: .SWAP, coin: given.coin, amount: given.amount, price: nil,
                                   timeMillis: given.timeMillis, externalId: externalId, localTime: given.localTime),
                toCoin: received.coin,
                toAmount: received.amount,
                swapValue: valueRow.swapValue,
                localTime: given.localTime
            ))
            i += 2
        }
        return out
    }

    /// Eine Zeile samt Vorzeichen der Menge in der Datei (−1 negativ, sonst 1; für Tausch-Seiten).
    private static func row(_ format: CsvImportFormat, _ h: Header, _ rec: Record, hint: Character?, zone: String,
                            monthFirst: Bool) throws -> (row: CsvImportRow, sign: Int) {
        func cell(_ role: Role) -> String {
            guard let i = h.index[role], i < rec.cells.count else { return "" }
            return rec.cells[i]
        }
        func number(_ role: Role) throws -> Decimal? {
            let t = cell(role)
            // «-» / «–» = leer (manche Exporte für fehlende Gebühr)
            if t.isEmpty || t == "-" || t == "–" { return nil }
            guard let v = parseNumber(t, hint: hint) else { throw Skip(reason: .BAD_NUMBER) }
            return v
        }
        func nonEmpty(_ s: String) -> String? { s.isEmpty ? nil : s }

        let rawKind = cell(.kind)
        guard let kind = classify(rawKind) else { throw Skip(reason: .UNSUPPORTED_KIND) }

        // Coin: Relai nur Bitcoin; Swissquote nur Zeilen ohne ISIN (Aktien/ETF haben eine)
        let coinText: String
        switch format {
        case .RELAI:
            coinText = nonEmpty(cell(.coin)) ?? "BTC"
        case .SWISSQUOTE:
            if !cell(.isin).isEmpty || cell(.coin).isEmpty { throw Skip(reason: .NOT_CRYPTO) }
            coinText = cell(.coin)
        case .KRAKEN, .BINANCE, .COINBASE, .BITPANDA, .GENERIC, .UNKNOWN:
            coinText = cell(.coin)
        }
        let coin = coinText.uppercased()
        if coin.isEmpty { throw Skip(reason: .MISSING_FIELD) }
        // Nur Geld («EUR»-Einzahlung u. ä.): kein Krypto
        if CsvImport.isFiat(coin) { throw Skip(reason: .NOT_CRYPTO) }

        guard let time = parseTime(cell(.date), zone: zone, monthFirst: monthFirst) else { throw Skip(reason: .BAD_DATE) }
        // Ohne Versatz: Kalenderzeit für den Schlüssel (unabhängig von der Zeitzone)
        let local: Int64? = localTime(cell(.date), monthFirst: monthFirst)

        var amountValue = try number(.amount)
        if amountValue == nil, let sats = try number(.amountSats) { amountValue = sats / satsPerBtc }
        guard var amount = amountValue else { throw Skip(reason: .MISSING_FIELD) }
        let sign = amount < 0 ? -1 : 1
        if amount < 0 { amount = -amount }
        if amount == 0 { throw Skip(reason: .BAD_NUMBER) }

        let pairParts = cell(.pair).split(omittingEmptySubsequences: false, whereSeparator: { $0 == "/" || $0 == "-" })
        let pairQuote = pairParts.count == 2 ? trim(String(pairParts[1])) : ""
        let quote = nonEmpty((nonEmpty(cell(.netCurrency)) ?? nonEmpty(cell(.quote)) ?? pairQuote).uppercased())

        var price = try number(.price).map { $0 < 0 ? -$0 : $0 }
        if let p = price, p == 0 { price = nil }
        // Belohnung mit Fiat-Betrag (wie Coinbase/Bitpanda): Preis = Betrag / Menge — nur mit Geld als
        // Preiswährung (Fiat oder USDT), sonst bliebe sie ohne Wert bzw. würde mangels Währung übersprungen. Wie Android
        let rewardValue = kind == .REWARD && (CsvImport.isFiat(quote) || quote == PortfolioStables.valuation)
        if price == nil && (kind == .BUY || kind == .SELL || rewardValue) {
            // Belohnung mit unlesbarem Betrag: ohne Preis übernehmen, nicht überspringen
            let fiatAmount: Decimal?
            if kind == .REWARD { fiatAmount = try? number(.fiatAmount) } else { fiatAmount = try number(.fiatAmount) }
            if let fiat = fiatAmount {
                let f = fiat < 0 ? -fiat : fiat
                if f > 0 { price = CsvImport.divide(f, amount) }
            }
        }
        // Tausch: kein Preis, sondern Wert (Fiat-Betrag, sonst Preis × Menge) — nur in Geld, sonst unbekannt
        var swapValue: Decimal?
        var toCoin: String?
        var toAmount: Decimal?
        if kind == .SWAP {
            var value: Decimal?
            if let fiat = try number(.fiatAmount) {
                let f = fiat < 0 ? -fiat : fiat
                if f > 0 { value = f }
            }
            if value == nil, let p = price { value = p * amount }
            if let q = quote, CsvImport.money.contains(q) { swapValue = value }
            price = nil
            toCoin = nonEmpty(cell(.toCoin).uppercased())
            toAmount = try number(.toAmount).map { $0 < 0 ? -$0 : $0 }
            if let t = toAmount, t == 0 { toAmount = nil }
            if toCoin == nil || toAmount == nil || toCoin == coin { toCoin = nil; toAmount = nil }
        }
        if price != nil && quote == nil { throw Skip(reason: .MISSING_FIELD) }

        var fee = try number(.fee).map { $0 < 0 ? -$0 : $0 }
        if let f = fee, f == 0 { fee = nil }
        var feeUnit = nonEmpty(cell(.feeUnit).uppercased())
        if var f = fee {
            if let u = feeUnit, ["SATS", "SAT", "SATOSHI", "SATOSHIS"].contains(u) {
                f = f / satsPerBtc
                fee = f
                feeUnit = "BTC"
            }
            if feeUnit == nil {
                guard let q = quote else { throw Skip(reason: .MISSING_FIELD) }
                feeUnit = q
            }
        } else {
            feeUnit = nil
        }

        // Swissquote: «00000000» = ohne Auftrag (Gebühren, Zahlungen)
        let idText = cell(.id)
        let externalId: String? = (!idText.isEmpty && idText.contains { $0 != "0" }) ? idText : nil

        let draft = CsvImportRow(
            line: rec.line,
            kind: kind,
            rawKind: rawKind,
            coin: coin,
            amount: amount,
            price: price,
            quote: quote,
            fee: fee,
            feeUnit: feeUnit,
            timeMillis: time,
            externalId: externalId,
            key: CsvImport.key(format: format, kind: kind, coin: coin, amount: amount, price: price,
                               timeMillis: time, externalId: externalId, localTime: local),
            toCoin: toCoin,
            toAmount: toAmount,
            swapValue: swapValue,
            localTime: local
        )
        // Gegen Stablecoin bzw. Krypto-Coin: Tausch
        var stableAmount: Decimal?
        if kind == .BUY || kind == .SELL, CsvImport.isStable(quote) || CsvImport.isCryptoQuote(quote),
           let fiat = try number(.fiatAmount) {
            let f = fiat < 0 ? -fiat : fiat
            if f > 0 { stableAmount = f }
        }
        return (stableSwap(format, draft, stableAmount: stableAmount) ?? draft, sign)
    }
}
