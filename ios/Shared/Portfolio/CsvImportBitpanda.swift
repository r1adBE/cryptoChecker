import Foundation

/// Bitpanda «Transaction history» → `CsvImportRow` — wie Android `CsvImportBitpanda` (Regeln und Begründungen dort).
/// Aufbau nur aus der öffentlichen Beschreibung bekannt (synthetische Testdateien in `Tests/Csv/`), echte Beispiele folgen.
///
/// Kopfblock vor der Kopfzeile überspringt die Kopfzeilensuche. Nur «Asset class» Kryptowährung (sonst `.NOT_CRYPTO`);
/// «buy»/«sell» → Kauf/Verkauf zu Amount Fiat / Amount Asset (Spread steckt im Fiat-Betrag), Gebühr in «Fee asset»;
/// «deposit»/«withdrawal» → Ein-/Auszahlung; «reward» → Belohnung zum Marktpreis; «transfer(stake)»/«transfer(unstake)» →
/// `.INTERNAL_MOVE`; «transfer» und Rest → Prüfliste. Fehlende Werte als «-».
enum CsvImportBitpanda {

    private static let required: Set<String> = ["transactionid", "timestamp", "transactiontype", "inout", "amountasset", "asset"]

    static func matches(_ names: Set<String>) -> Bool { required.isSubset(of: names) }

    private static let internalTypes: Set<String> = ["transferstake", "transferunstake"]

    private static func kind(_ type: String) -> CsvImportKind? {
        switch type {
        case "buy": return .BUY
        case "sell": return .SELL
        case "deposit": return .DEPOSIT
        case "withdrawal": return .WITHDRAWAL
        case "reward": return .REWARD
        default: return nil
        }
    }

    private struct Skip: Error {
        let reason: CsvImportSkipReason
    }

    static func build(names: [String], data: [CsvImportParser.Record], zone: String) -> CsvImportResult {
        func col(_ n: String) -> Int? { names.firstIndex(of: n) }
        let iId = col("transactionid")
        let iTime = col("timestamp")
        let iType = col("transactiontype")
        let iFiatAmount = col("amountfiat")
        let iFiat = col("fiat")
        let iAmount = col("amountasset")
        let iAsset = col("asset")
        let iMarketPrice = col("assetmarketprice")
        let iMarketCurrency = col("assetmarketpricecurrency")
        let iClass = col("assetclass")
        let iFee = col("fee")
        let iFeeAsset = col("feeasset")
        var rows: [CsvImportRow] = []
        var skipped: [CsvImportSkip] = []
        var localTimes = false
        for rec in data {
            func cell(_ i: Int?) -> String {
                guard let i, i < rec.cells.count else { return "" }
                return rec.cells[i] == "-" ? "" : rec.cells[i]
            }
            func number(_ i: Int?) throws -> Decimal? {
                let t = cell(i)
                if t.isEmpty { return nil }
                guard let v = CsvImportParser.parseNumber(t, hint: ".") else { throw Skip(reason: .BAD_NUMBER) }
                return v
            }
            func positive(_ value: Decimal?) -> Decimal? { value.map { abs($0) }.flatMap { $0 > 0 ? $0 : nil } }
            func nonEmpty(_ s: String) -> String? { s.isEmpty ? nil : s }
            do {
                let type = CsvImportParser.normalize(cell(iType))
                guard let kind = Self.kind(type) else {
                    throw Skip(reason: internalTypes.contains(type) ? .INTERNAL_MOVE : .UNSUPPORTED_KIND)
                }
                let coin = cell(iAsset).uppercased()
                if coin.isEmpty { throw Skip(reason: .MISSING_FIELD) }
                let assetClass = CsvImportParser.normalize(cell(iClass))
                if CsvImport.isFiat(coin) || (!assetClass.isEmpty && !assetClass.contains("crypto")) {
                    throw Skip(reason: .NOT_CRYPTO)
                }
                let timeText = cell(iTime)
                guard let time = CsvImportParser.parseTime(timeText, zone: zone) else { throw Skip(reason: .BAD_DATE) }
                let local = CsvImportParser.localTime(timeText)
                if local != nil { localTimes = true }
                guard let rawAmount = try number(iAmount) else { throw Skip(reason: .MISSING_FIELD) }
                let amount = abs(rawAmount)
                if amount == 0 { throw Skip(reason: .BAD_NUMBER) }
                let fiatAmount = try positive(number(iFiatAmount))
                let fiat = nonEmpty(cell(iFiat).uppercased())
                let marketPrice = try positive(number(iMarketPrice))
                let marketCurrency = nonEmpty(cell(iMarketCurrency).uppercased())
                // Kauf/Verkauf: bezahlter bzw. erhaltener Fiat-Betrag je Coin; Belohnung: Marktpreis beim Erhalt
                var price: Decimal?
                var quote: String?
                if kind == .BUY || kind == .SELL || kind == .REWARD {
                    if kind != .REWARD, let paid = fiatAmount, let fiat {
                        price = PortfolioDecimal.divide(paid, amount)
                        quote = fiat
                    } else if let marketPrice, let marketCurrency {
                        price = marketPrice
                        quote = marketCurrency
                    }
                }
                let fee = try positive(number(iFee))
                let feeUnit = nonEmpty(cell(iFeeAsset).uppercased())
                if fee != nil && feeUnit == nil { throw Skip(reason: .MISSING_FIELD) }
                let externalId = nonEmpty(cell(iId))
                rows.append(CsvImportRow(
                    line: rec.line, kind: kind, rawKind: cell(iType), coin: coin, amount: amount, price: price, quote: quote,
                    fee: fee, feeUnit: fee == nil ? nil : feeUnit, timeMillis: time, externalId: externalId,
                    key: CsvImport.key(format: .BITPANDA, kind: kind, coin: coin, amount: amount, price: price,
                                       timeMillis: time, externalId: externalId, localTime: local),
                    localTime: local))
            } catch let s as Skip {
                skipped.append(CsvImportSkip(line: rec.line, reason: s.reason))
            } catch {
                skipped.append(CsvImportSkip(line: rec.line, reason: .MISSING_FIELD))
            }
        }
        return CsvImport.finish(.BITPANDA, rows: rows, skipped: skipped, zone: zone, localTimes: localTimes)
    }
}
