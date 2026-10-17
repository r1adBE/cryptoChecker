import Foundation

/// Neutrale Art einer importierten Zeile — vor der Zuordnung zu den Buchungsarten des Portfolios
/// (erst bei der Integration). Gespiegelt in `CsvImportRow.kt` (Android).
enum CsvImportKind: String, CaseIterable {
    /// `TRANSFER`: eigene Mittel zurück aus Staking/Earn («Unstaking», «Redeem») — Umbuchung, kein Gewinn, keine Belohnung.
    case BUY, SELL, WITHDRAWAL, DEPOSIT, REWARD, FEE, SWAP, TRANSFER
}

/// Reihenfolge von Tag und Monat in Daten wie «03/04/2024» (nur Trenner «/» und «-»; «03.04.2024» ist immer Tag
/// zuerst). Erkannt je Datei (`CsvImportParser.detectDateOrder`); nur mehrdeutige Daten → Vorschau fragt. Wie Android.
enum CsvDateOrder: String, CaseIterable {
    case DAY_FIRST, MONTH_FIRST
}

/// Erkanntes Dateiformat; UNKNOWN = keine bekannte Kopfzeile gefunden. Der Name steht in Schlüsseln und Importläufen
/// (`BINANCE|…`) — nie umbenennen. Wie Android.
enum CsvImportFormat: String, CaseIterable {
    case RELAI, SWISSQUOTE, KRAKEN, BINANCE, COINBASE, BITPANDA, GENERIC, UNKNOWN

    /// Name der Börse bzw. des Anbieters (Marke, nicht übersetzt) — Vorschau, Liste «Importe» und Herkunft einer
    /// Umbuchung (`CsvTransferMatch`); nil = Herkunft unbekannt (eigene CSV). Wie Android `exchangeName`.
    var exchangeName: String? {
        switch self {
        case .RELAI: return "Relai"
        case .SWISSQUOTE: return "Swissquote"
        case .KRAKEN: return "Kraken"
        case .BINANCE: return "Binance"
        case .COINBASE: return "Coinbase"
        case .BITPANDA: return "Bitpanda"
        case .GENERIC, .UNKNOWN: return nil
        }
    }
}

/// Warum eine Zeile nicht übernommen wurde (für die Vorschau). `FEE_EXCEEDS_AMOUNT`: Gebühr im Coin ≥ erhaltene Menge
/// (`CsvImport.feeConsumesAmount`) — wie das Erfassen-Blatt nicht speicherbar. `INTERNAL_MOVE`: Verschiebung zwischen
/// Konten derselben Börse (Spot ↔ Earn/Funding, Coinbase ↔ Coinbase Pro) — nichts zu buchen. `POSSIBLE_DUPLICATE`: fast
/// gleich wie ein schon importiertes Geschäft (anderer Binance-Export, `CsvImportBinance.possibleDuplicates`). Wie Android.
enum CsvImportSkipReason: String {
    case UNSUPPORTED_KIND, NOT_CRYPTO, BAD_NUMBER, BAD_DATE, MISSING_FIELD, FEE_EXCEEDS_AMOUNT, TWO_FEES, INTERNAL_MOVE
    case POSSIBLE_DUPLICATE
    /// Mehrere Geschäfte in derselben Sekunde, nicht eindeutig trennbar (Binance: mehrere Coins je Seite) — wie Android.
    case AMBIGUOUS_GROUP
}

/// Übersprungene Zeile; `line` = Zeilennummer in der Datei (1 = erste Zeile).
struct CsvImportSkip: Equatable {
    let line: Int
    let reason: CsvImportSkipReason
}

/// Eine importierte Buchung — Felder wie Android (`CsvImportRow`): Menge exakt und > 0 (Sats bereits
/// in BTC), Preis je Coin in `quote` (nil = unbekannt), Gebühr > 0 oder nil, Zeit UTC (Ortszeit der
/// Datei ohne Versatz = Europe/Zurich), `key` gegen doppelten Import.
struct CsvImportRow: Equatable {
    let line: Int
    let kind: CsvImportKind
    let rawKind: String
    let coin: String
    let amount: Decimal
    let price: Decimal?
    let quote: String?
    let fee: Decimal?
    let feeUnit: String?
    let timeMillis: Int64
    let externalId: String?
    var key: String
    /// Tausch (`.SWAP`): erhaltener Coin (gross); `coin`/`amount` = abgegeben.
    var toCoin: String? = nil
    /// Tausch: erhaltene Menge (> 0).
    var toAmount: Decimal? = nil
    /// Tausch: Wert in `quote` (Fiat/USD aus der Datei); nil = unbekannt.
    var swapValue: Decimal? = nil
    /// Kalenderzeit der Datei (als wäre sie UTC), wenn sie ohne Versatz steht — Schlüssel unabhängig von der gewählten
    /// Zeitzone (`CsvImport.key`); nil = Zeit mit Versatz/«Z».
    var localTime: Int64? = nil
    /// Frühere Schlüssel derselben Zeile (Zeit je Zeitzone in UTC, bis Build 45; Kauf/Verkauf gegen Stablecoin bis
    /// Build 46) — erkennt ältere Importe.
    var legacyKeys: [String] = []
    /// Kauf/Verkauf gegen einen Stablecoin, als Tausch gebucht (`CsvImport.stables`) — Hinweis in der Vorschau.
    var stableSwap = false
}

struct CsvImportResult {
    let format: CsvImportFormat
    let rows: [CsvImportRow]
    let skipped: [CsvImportSkip]
    /// Zeitzone der Zeiten ohne Versatz/«Z» (`CsvImportParser.zoneFor`).
    var zone: String = CsvImportParser.defaultZone
    /// Mindestens eine gelesene Zeit ohne Versatz — nur dann zählt `zone` (Vorschau zeigt sie mit Auswahl).
    var localTimes = false
    /// Gelesene Reihenfolge Tag/Monat (`CsvDateOrder`).
    var dateOrder: CsvDateOrder = .DAY_FIRST
    /// Nur mehrdeutige Daten («03/04/2024»): Vorschau bietet «Tag/Monat» / «Monat/Tag» an (`dateOrder` = Auswahl).
    var dateOrderAmbiguous = false
    /// Datei verrät kein Dezimalzeichen, enthält aber eine mehrdeutige Zahl («42,000»): diese; die Vorschau fragt «42000
    /// oder 42?» (`decimalMark` = gelesenes Dezimalzeichen). nil = eindeutig.
    var decimalSample: String? = nil
    /// Dezimalzeichen bei `decimalSample` («.» oder «,»); sonst nil.
    var decimalMark: Character? = nil
    /// Erkannte Spalten der Kopfzeile (Name wie in der Datei) — Vorschau.
    var columns: [String] = []
    /// Zeitzone der Tageskurse (Devisen) = Zeitzone des Geräts, wie der Steuer-Export — wie Android `fxZone`.
    var fxZone: String = CsvImportParser.defaultZone

    /// Vorschau fragt nach dem Dezimalzeichen (`decimalSample`).
    var decimalAmbiguous: Bool { decimalSample != nil }
}

/// Aufteilung für die Vorschau: neu vs. bereits importiert.
struct CsvImportSplit {
    let fresh: [CsvImportRow]
    let duplicates: [CsvImportRow]
}

/// Zeile mit Preis in USDT (Einheit des Portfolios); siehe Android `CsvImportPriced`.
struct CsvImportPriced {
    let row: CsvImportRow
    /// Preis je Coin in USDT; nil ohne Preis oder ohne Tageskurs.
    let priceUsdt: Decimal?
    /// Gebühr in USDT, wenn sie in der Preiswährung oder USD/USDT lautet; sonst nil.
    let feeUsdt: Decimal?
    /// Tageskurs fehlt.
    let needsFx: Bool
    /// Kauf/Verkauf ohne Preis in der Datei.
    let missingPrice: Bool
    /// Tausch: Wert in USDT; nil = unbekannt (kein Wert in der Datei oder kein Tageskurs).
    var swapValueUsdt: Decimal? = nil
}

/// Schlüssel, Duplikate und Umrechnung in USDT — wie Android (`CsvImport`).
enum CsvImport {

    /// Nachkommastellen für abgeleitete Preise (Teilungen), halbe auf gerade.
    static let derivedScale = 12

    /// Währungen, die 1:1 als USDT gelten.
    private static let usdLike: Set<String> = ["USD", "USDT"]

    /// Fiat (mit USD): nur Geschäfte gegen diese Währungen sind Kauf/Verkauf; Ein-/Auszahlungen davon sind kein Krypto.
    static let fiat: Set<String> = ["CHF", "EUR", "USD", "GBP", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK",
                                    "PLN", "CZK", "HUF"]

    /// Wertwährungen eines Tauschs: Fiat und USDT (Bewertungswährung, gilt 1:1). Nur ein Wert in diesen Währungen
    /// gilt als Wert eines Tauschs; sonst unbekannt (Tagesschluss beim Rechnen).
    static let money: Set<String> = fiat.union(["USDT"])

    /// Stablecoins (Liste des Portfolios, `PortfolioStables.coins`, ohne «USD» = Fiat). Kauf/Verkauf dagegen ist
    /// Krypto gegen Krypto → Tausch (FR/AT: steuerneutral), der Stablecoin wird ein Bestand.
    static var stables: Set<String> { PortfolioStables.coins.subtracting(["USD"]) }

    static func isStable(_ code: String?) -> Bool {
        guard let code else { return false }
        return stables.contains(code.trimmingCharacters(in: .whitespaces).uppercased())
    }

    /// Fiat-Währung (`fxFiat`) — als Coin einer Zeile kein Krypto. Wie Android.
    static func isFiat(_ code: String?) -> Bool {
        guard let code else { return false }
        return fxFiat.contains(code.trimmingCharacters(in: .whitespaces).uppercased())
    }

    /// Fiat mit Tageskurs: nie Krypto als Preiswährung, als Coin übersprungen (`isFiat`). Gleich wie
    /// `FxRateSource.currencies` (Test `CsvImportTests.testFiatListMatchesFxCurrencies`).
    static let fxFiat: Set<String> = fiat.union([
        "RON", "BGN", "ISK", "TRY", "CNY", "HKD", "SGD", "KRW", "INR", "IDR", "THB",
        "MYR", "PHP", "NZD", "ZAR", "BRL", "MXN", "ILS",
    ])

    /// Preiswährung ist ein Krypto-Coin (Paar «ETH/BTC»): weder Geld (`money`), Fiat mit Tageskurs noch Stablecoin
    /// (der hat `isStable`). Kauf/Verkauf dagegen ist ein Tausch (`CsvImportParser.stableSwap`). Wie Android.
    static func isCryptoQuote(_ code: String?) -> Bool {
        guard let code else { return false }
        let c = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !c.isEmpty else { return false }
        return !money.contains(c) && !fxFiat.contains(c) && !stables.contains(c)
            && c.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
    }

    /// Wert eines Tauschs mit Stablecoin-Seite: USDT-Seite = Wert in USDT (1:1 wie im Portfolio); andere Stablecoins
    /// (USDC …) nicht (gilt wie im Portfolio ihr Tagesschluss) → (nil, nil). Wie Android `stableSwapValue`.
    static func stableSwapValue(coin: String, amount: Decimal, toCoin: String,
                                toAmount: Decimal) -> (value: Decimal?, quote: String?) {
        if coin == PortfolioStables.valuation { return (amount, PortfolioStables.valuation) }
        if toCoin == PortfolioStables.valuation { return (toAmount, PortfolioStables.valuation) }
        return (nil, nil)
    }

    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Exakter Text ohne Exponent und Nullen am Ende («0.00284516», «2.9», «0»).
    static func text(_ value: Decimal) -> String {
        if value == 0 { return "0" }
        var v = value
        NSDecimalCompact(&v)
        return NSDecimalString(&v, posix)
    }

    /// Auf `scale` Nachkommastellen, halbe auf gerade (wie `RoundingMode.HALF_EVEN`).
    static func round(_ value: Decimal, scale: Int) -> Decimal {
        var v = value
        var r = Decimal()
        NSDecimalRound(&r, &v, scale, .bankers)
        return r
    }

    /// `a / b` auf `derivedScale` Stellen (wie Android `divide(b, 12, HALF_EVEN)`).
    static func divide(_ a: Decimal, _ b: Decimal) -> Decimal {
        round(a / b, scale: derivedScale)
    }

    /// Schlüssel gegen doppelten Import (Aufbau wie Android `CsvImport.key`). Zeit: UTC-ms; steht sie ohne Versatz in
    /// der Datei (`localTime`), «L» + Kalenderzeit — eine andere Zeitzone beim zweiten Import ändert ihn nicht.
    static func key(format: CsvImportFormat, kind: CsvImportKind, coin: String, amount: Decimal,
                    price: Decimal?, timeMillis: Int64, externalId: String?, localTime: Int64? = nil) -> String {
        let time: String = localTime.map { "L\($0)" } ?? String(timeMillis)
        if let externalId {
            return format == .SWISSQUOTE
                ? "SWISSQUOTE|\(externalId)|\(time)|\(text(amount))"
                : "\(format.rawValue)|\(externalId)"
        }
        return "\(format.rawValue)|\(kind.rawValue)|\(coin)|\(time)|\(text(amount))|\(price.map { text($0) } ?? "-")"
    }

    /// Wiederholte Schlüssel eindeutig machen (zweites Vorkommen «#2» usw.), Reihenfolge bleibt.
    static func withOccurrence(_ keys: [String]) -> [String] {
        var seen: [String: Int] = [:]
        return keys.map { k in
            let n = (seen[k] ?? 0) + 1
            seen[k] = n
            return n == 1 ? k : "\(k)#\(n)"
        }
    }

    /// Gebühr eines Tauschs aus beiden Seiten (wie Android `CsvImport.swapFee`): eine Seite → diese; beide in
    /// derselben Einheit (Gross/Klein egal, beide ohne Einheit zählt als gleich) → Summe; verschiedene → nil.
    static func swapFee(givenFee: Decimal?, givenUnit: String?,
                        receivedFee: Decimal?, receivedUnit: String?) -> (fee: Decimal?, unit: String?)? {
        guard let gFee = givenFee else { return (receivedFee, receivedUnit) }
        guard let rFee = receivedFee else { return (gFee, givenUnit) }
        let gu: String = (givenUnit ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        let ru: String = (receivedUnit ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        guard gu == ru else { return nil }
        let sum: Decimal = gFee + rFee
        return (sum, givenUnit)
    }

    /// Gebühren eines Geschäfts in mehreren Einheiten (Binance, Kraken; `fees` = (Einheit, Menge > 0), je Einheit
    /// höchstens einmal) — wie Android `CsvImport.splitFees`: eine bleibt am Geschäft, jede weitere wird eine eigene
    /// Gebühr-Zeile (`.FEE`, Veräusserung zum Tageskurs). Am Geschäft bleibt, unabhängig von der Reihenfolge: die
    /// Fiat-Gebühr, sonst die im abgegebenen, dann im erhaltenen Coin, sonst die alphabetisch erste. Zwei Fiat-Gebühren
    /// → nil (`.TWO_FEES`).
    static func splitFees(_ fees: [(unit: String, amount: Decimal)], given: String?,
                          received: String?) -> (primary: (unit: String, amount: Decimal)?, extra: [(unit: String, amount: Decimal)])? {
        if fees.count <= 1 { return (fees.first, []) }
        if fees.filter({ isFiat($0.unit) }).count > 1 { return nil }
        let primary = fees.first(where: { isFiat($0.unit) })
            ?? fees.first(where: { $0.unit == given })
            ?? fees.first(where: { $0.unit == received })
            ?? fees.sorted(by: { $0.unit < $1.unit })[0]
        return (primary, fees.filter { $0.unit != primary.unit })
    }

    /// Gebühr verbraucht die ganze erhaltene Menge (wie `PortfolioTxForm.feeConsumesReceived`): Kauf/Einzahlung/Belohnung
    /// mit Gebühr im Coin der Zeile ≥ Menge; Tausch mit Gebühr im erhaltenen Coin ≥ erhaltener Menge. Wie Android.
    static func feeConsumesAmount(_ row: CsvImportRow) -> Bool {
        guard let fee = row.fee, fee > 0,
              let unit = row.feeUnit?.trimmingCharacters(in: .whitespaces).uppercased() else { return false }
        switch row.kind {
        case .BUY, .DEPOSIT, .REWARD:
            return unit == row.coin.trimmingCharacters(in: .whitespaces).uppercased() && fee >= row.amount
        case .SWAP:
            guard let to = row.toCoin?.trimmingCharacters(in: .whitespaces).uppercased(),
                  let toAmount = row.toAmount else { return false }
            return unit == to && fee >= toAmount
        default:
            return false
        }
    }

    /// Zeilen, deren Schlüssel (oder ein früherer, `legacyKeys`) schon gespeichert ist, als Duplikate abtrennen.
    /// `existingKeys` = alle gespeicherten Herkunftsschlüssel samt `linkedImportKey` verknüpfter Umbuchungen: jede ihrer
    /// Seiten gilt als importiert. Vor Build 58 verknüpfte Umbuchungen tragen lange Teile gekürzt
    /// (`LegacyStorageMigration.shortKey`) — auch darüber erkannt. Wie Android.
    static func splitDuplicates(_ rows: [CsvImportRow], existingKeys: Set<String>) -> CsvImportSplit {
        func isKnown(_ key: String) -> Bool {
            existingKeys.contains(key) || existingKeys.contains(LegacyStorageMigration.shortKey(key))
        }
        func known(_ r: CsvImportRow) -> Bool { isKnown(r.key) || r.legacyKeys.contains(where: isKnown) }
        return CsvImportSplit(fresh: rows.filter { !known($0) }, duplicates: rows.filter { known($0) })
    }

    /// Gemeinsamer Abschluss der Börsen-Leser (Kraken, Binance, Coinbase, Bitpanda) — wie Android `finish`: Zeilen nach
    /// Zeit (dann Zeile), gleiche Schlüssel «#2» …, Gebühr ≥ erhaltene Menge übersprungen, Übersprungene nach Zeile.
    /// `localTimes`: die Datei schreibt Zeiten ohne Versatz (dann zählt die Zeitzone, sofern Zeilen übrig bleiben).
    static func finish(_ format: CsvImportFormat, rows: [CsvImportRow], skipped: [CsvImportSkip], zone: String,
                       localTimes: Bool) -> CsvImportResult {
        // stabil nach Zeit, dann Zeile (wie Android `sortedWith(compareBy({ time }, { line }))`)
        var sorted = rows.enumerated().sorted { a, b in
            (a.element.timeMillis, a.element.line, a.offset) < (b.element.timeMillis, b.element.line, b.offset)
        }.map(\.element)
        let keys = withOccurrence(sorted.map(\.key))
        for i in sorted.indices { sorted[i].key = keys[i] }
        var out = skipped
        let drafts: [CsvImportRow] = sorted.filter { r -> Bool in
            let consumes = feeConsumesAmount(r)
            if consumes { out.append(CsvImportSkip(line: r.line, reason: .FEE_EXCEEDS_AMOUNT)) }
            return !consumes
        }
        out = out.enumerated().sorted { ($0.element.line, $0.offset) < ($1.element.line, $1.offset) }.map(\.element)
        return CsvImportResult(format: format, rows: drafts, skipped: out, zone: zone, localTimes: localTimes && !drafts.isEmpty)
    }

    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// Kalendertag (Tage seit 1970-01-01) des Zeitpunkts in `zone` — für die Tageskurse.
    static func epochDay(_ timeMillis: Int64, zone: String = CsvImportParser.defaultZone) -> Int64 {
        let offset = Int64(CsvImportParser.timeZone(zone).secondsFromGMT(for: Date(timeIntervalSince1970: Double(timeMillis) / 1000)))
        return floorDiv(floorDiv(timeMillis, 1000) + offset, 86_400)
    }

    /// Preise in USDT umrechnen. `fx(Währung, Tag)` = Einheiten dieser Währung je 1 USD; nil = kein Kurs.
    static func priceInUsdt(_ rows: [CsvImportRow], zone: String = CsvImportParser.defaultZone,
                            fx: (String, Int64) -> Decimal?) -> [CsvImportPriced] {
        rows.map { row -> CsvImportPriced in
            let day = epochDay(row.timeMillis, zone: zone)
            var needsFx = false
            var priceUsdt: Decimal?
            if let p = row.price {
                if let q = row.quote, let r = rate(q, day, fx) {
                    priceUsdt = toUsdt(p, r)
                } else {
                    needsFx = true
                }
            }
            // Tausch: Wert aus der Datei (Fiat) zum Tageskurs
            var swapValueUsdt: Decimal?
            if let v = row.swapValue {
                if let q = row.quote, let r = rate(q, day, fx) {
                    swapValueUsdt = toUsdt(v, r)
                } else {
                    needsFx = true
                }
            }
            var feeUsdt: Decimal?
            if let fee = row.fee, let unit = row.feeUnit, unit == row.quote || usdLike.contains(unit) {
                if let r = rate(unit, day, fx) {
                    feeUsdt = toUsdt(fee, r)
                } else {
                    needsFx = true
                }
            }
            return CsvImportPriced(row: row, priceUsdt: priceUsdt, feeUsdt: feeUsdt, needsFx: needsFx,
                                   missingPrice: row.price == nil && (row.kind == .BUY || row.kind == .SELL),
                                   swapValueUsdt: swapValueUsdt)
        }
    }

    /// Kurs der Währung am Tag; USD/USDT = 1; ungültig = nil.
    private static func rate(_ currency: String, _ day: Int64, _ fx: (String, Int64) -> Decimal?) -> Decimal? {
        if usdLike.contains(currency) { return 1 }
        guard let r = fx(currency, day), r > 0 else { return nil }
        return r
    }

    private static func toUsdt(_ value: Decimal, _ rate: Decimal) -> Decimal {
        rate == 1 ? value : divide(value, rate)
    }
}
