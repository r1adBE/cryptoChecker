import CryptoKit
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
    /// Kraken-Zeile mit schon gesehener txid, aber anderer Menge — nicht still verwerfen, von Hand prüfen. Wie Android.
    case DUPLICATE_TXID_DIFFERENT
    /// Kraken-Ein-/Auszahlung einer Staking-Variante («ETH2.S», «DOT.S») ohne Gegenbuchung — Bestand prüfen. Wie Android.
    case STAKING_UNPAIRED
    /// Menge rundet auf den gespeicherten 18 Nachkommastellen auf 0 — würde beim Speichern abgelehnt
    /// (`CsvImportMapping.unsavableReason`). Wie Android.
    case AMOUNT_TOO_SMALL
    /// Tausch ohne gültigen erhaltenen Coin bzw. ohne erhaltene Menge — würde beim Speichern abgelehnt. Wie Android.
    case INVALID_SWAP
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
    /// Zu viele Zellen je Zeile oder Datensätze (`CsvImportParser.maxCellsPerRow`, `maxRows`) — wie Android.
    var tooLarge = false

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
    /// USD/Fiat mit USDT = 1 USD umgerechnet, weil der USDT/USD-Tagesschluss DES Tags fehlt (Näherung wie
    /// `TaxExport.usdtApproximateOn` — die Vorschau nennt die Zeile). Wie Android.
    var usdtApproximate = false
}

/// Schlüssel, Duplikate und Umrechnung in USDT — wie Android (`CsvImport`).
enum CsvImport {

    /// Rechenwährung (USDT gilt 1:1). USD ist Fiat: in USDT über den USDT/USD-Tagesschluss (`priceInUsdt`).
    static let usdLike: Set<String> = ["USD", "USDT"]

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

    /// Schlüssel gegen doppelten Import (Aufbau wie Android `CsvImport.key`). Zeit: UTC-ms; steht sie ohne Versatz in
    /// der Datei (`localTime`), «L» + Kalenderzeit — eine andere Zeitzone beim zweiten Import ändert ihn nicht. Ein
    /// Schlüssel über `maxKeyBytes` wird in `withOccurrence` durch seinen Hash ersetzt (`stableKey`).
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

    /// Wiederholte Schlüssel eindeutig machen (zweites Vorkommen «#2» usw.), Reihenfolge bleibt; danach jeder Schlüssel
    /// in seiner gespeicherten Form (`stableKey`).
    static func withOccurrence(_ keys: [String]) -> [String] {
        var seen: [String: Int] = [:]
        return keys.map { k in
            let n = (seen[k] ?? 0) + 1
            seen[k] = n
            return stableKey(n == 1 ? k : "\(k)#\(n)")
        }
    }

    /// Höchstlänge eines Herkunftsschlüssels in UTF-8-Bytes — dieselbe Grenze wie beim Speichern
    /// (`PortfolioTx.maxImportKey`, auf beiden Plattformen in UTF-8-Bytes gezählt).
    static let maxKeyBytes = 200

    /// Gespeicherte Form eines Schlüssels: bis `maxKeyBytes` UTF-8-Bytes unverändert; länger (z. B. eine Kennung mit
    /// über 190 Zeichen) «<Format>|h:<SHA-256 des ganzen Schlüssels, hex>» — gleich lang, stabil über Importe und
    /// Plattformen, statt beim Speichern still zu entfallen (dann käme die Zeile beim nächsten Import doppelt). Wie Android.
    static func stableKey(_ key: String) -> String {
        let bytes = Data(key.utf8)
        guard bytes.count > maxKeyBytes else { return key }
        let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let format = key.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? key
        return "\(format)|h:\(hash)"
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

    /// Zeilen, deren Schlüssel schon gespeichert ist, als Duplikate abtrennen. `existingKeys` = alle gespeicherten
    /// Herkunftsschlüssel samt `linkedImportKey` verknüpfter Umbuchungen: jede ihrer Seiten gilt als importiert. Wie Android.
    static func splitDuplicates(_ rows: [CsvImportRow], existingKeys: Set<String>) -> CsvImportSplit {
        CsvImportSplit(fresh: rows.filter { !existingKeys.contains($0.key) },
                       duplicates: rows.filter { existingKeys.contains($0.key) })
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
}
