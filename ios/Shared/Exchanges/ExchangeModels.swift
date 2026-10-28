import Foundation

/// Art eines Futures-Kontrakts. Die Rohwerte sind dieselben wie auf Android, damit Sicherungen zwischen den
/// Plattformen austauschbar bleiben — nicht umnummerieren.
enum FuturesContractType: Int, Codable, CaseIterable, Comparable, Sendable {
    case none = 0
    case perpetual = 1
    case weekly = 2
    case biweekly = 3
    case monthly = 4
    case bimonthly = 5
    case quarterly = 6
    case biquarterly = 7
    case inversePerpetual = 10

    /// Sortiert nach der Position in `allCases` (= Reihenfolge des Kotlin-Enums), nicht nach dem Rohwert.
    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rank < rhs.rank }

    private var rank: Int { Self.allCases.firstIndex(of: self) ?? 0 }

    /// Bezeichnung in der Android-Sicherung (Name der Kotlin-Konstante).
    var backupName: String {
        switch self {
        case .none: "NONE"
        case .perpetual: "PERPETUAL"
        case .inversePerpetual: "INVERSE_PERPETUAL"
        case .weekly: "WEEKLY"
        case .biweekly: "BIWEEKLY"
        case .monthly: "MONTHLY"
        case .bimonthly: "BIMONTHLY"
        case .quarterly: "QUARTERLY"
        case .biquarterly: "BIQUARTERLY"
        }
    }

    /// Aus der Bezeichnung einer Sicherung; Unbekanntes wird `.none`.
    init(backupName: String) {
        self = Self.allCases.first(where: { $0.backupName == backupName }) ?? .none
    }

    static func fromInt(_ v: Int) -> Self { Self(rawValue: v) ?? .none }

    /// Kurzform für Anzeige und Beschreibung; nil bei Spot.
    var shortName: String? {
        switch self {
        case .none: nil
        case .perpetual: "Perp"
        case .inversePerpetual: "InvPerp"
        case .weekly: "1W"
        case .biweekly: "2W"
        case .monthly: "1M"
        case .bimonthly: "2M"
        case .quarterly: "1Q"
        case .biquarterly: "2Q"
        }
    }

    var isPerpetual: Bool {
        switch self {
        case .perpetual, .inversePerpetual: true
        default: false
        }
    }

    /// Kontrakte mit Verfall: Ihre Kennung wechselt beim Ablauf.
    var isRolling: Bool { self != .none && !isPerpetual }

    /// Verfallstag als «yyMMdd», abgelesen an der Kennung der Börse (etwa «BTCUSDT_251226» oder COIN-M
    /// «2:BTCUSD_251226»); nil, wenn kein Datum drinsteht (Perpetual). Abfragen richten sich
    /// immer nach dieser Kennung, nie nach dem Kalender. Wie `FuturesContractType.deliveryCodeOf` (Android).
    static func deliveryCode(of pairId: String?) -> String? {
        guard let trimmed = pairId?.trimmingCharacters(in: .whitespaces),
              let cut = trimmed.lastIndex(of: "_") else { return nil }
        let tail = trimmed[trimmed.index(after: cut)...]
        let onlyDigits = tail.allSatisfy { ch in ch.isASCII && ch.isNumber }
        return tail.count == 6 && onlyDigits ? String(tail) : nil
    }

    /// Erkennt an der Kennung ein inverses (in Coins abgerechnetes) Perpetual: Binance COIN-M «2:BTCUSD_PERP»,
    /// OKX «BTC-USD-SWAP», Deribit «BTC-PERPETUAL» (lineare Deribit-Paare heissen «SOL_USDC-PERPETUAL») —
    /// für die Paarlisten von OKX und Deribit. Wie `FuturesContractType.isInversePerpetualId` (Android).
    static func isInversePerpetualId(exchangeKey: String, pairId: String?) -> Bool {
        guard let id = pairId?.trimmingCharacters(in: .whitespaces), !id.isEmpty else { return false }
        if exchangeKey == "BinanceFutures" { return id.hasPrefix("2:") && deliveryCode(of: id) == nil }
        if exchangeKey == "OkexFutures" { return id.hasSuffix("-USD-SWAP") }
        if exchangeKey == "Deribit" { return id.hasSuffix("-PERPETUAL") && !id.contains("_") }
        return false
    }

    /// Verfall laut Kalender (UTC) — nur für die Anzeige und als Notlösung, wenn die Kennung kein Datum trägt.
    var deliveryDate: Date? { delivery(asOf: Date()) }

    /// Verfall laut Kalender zum Zeitpunkt `now` (Tests). Abgerechnet wird freitags um 08:00 UTC: Ab dann gilt schon der
    /// nächste Kontrakt — auch für Monat und Quartal, wenn ihr letzter Freitag vorbei ist (vorher z. B. am
    /// 31. Oktober noch der 30. Oktober). Wie Android `FuturesContractType.getDeliveryDate`.
    func delivery(asOf now: Date) -> Date? {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let friday = 6 // gregorianisch: Sonntag = 1
        let settlementHour = 8
        // Erster Tag, an dem ein Kontrakt noch läuft: heute, ab der Abrechnungsstunde erst morgen
        let today = utc.startOfDay(for: now)
        let start = utc.component(.hour, from: now) >= settlementHour ? utc.date(byAdding: .day, value: 1, to: today)! : today

        func monthStart(_ day: Date) -> Date { utc.date(from: utc.dateComponents([.year, .month], from: day))! }
        func adding(months: Int, to day: Date) -> Date { utc.date(byAdding: .month, value: months, to: day)! }
        // Nächster Freitag ab `day` (der Tag selbst, wenn er schon einer ist)
        func fridayFrom(_ day: Date) -> Date {
            guard utc.component(.weekday, from: day) != friday else { return day }
            return utc.nextDate(after: day, matching: DateComponents(weekday: friday), matchingPolicy: .nextTime) ?? day
        }
        // Letzter Freitag des Monats, in dem `day` liegt
        func lastFriday(of day: Date) -> Date {
            var probe = utc.date(byAdding: .day, value: -1, to: adding(months: 1, to: monthStart(day)))!
            while utc.component(.weekday, from: probe) != friday {
                probe = utc.date(byAdding: .day, value: -1, to: probe)!
            }
            return probe
        }
        // Letzter Freitag im letzten Monat des Quartals von `day`
        func quarterEnd(_ day: Date) -> Date {
            let quarterIndex = (utc.component(.month, from: day) - 1) / 3
            var parts = utc.dateComponents([.year], from: day)
            parts.month = quarterIndex * 3 + 3
            parts.day = 1
            return lastFriday(of: utc.date(from: parts)!)
        }
        // Laufender Monats- bzw. Quartalskontrakt: ist sein letzter Freitag vorbei, der nächste
        func currentMonthly() -> Date {
            let end = lastFriday(of: start)
            return end >= start ? end : lastFriday(of: adding(months: 1, to: monthStart(start)))
        }
        func currentQuarterly() -> Date {
            let end = quarterEnd(start)
            return end >= start ? end : quarterEnd(adding(months: 3, to: monthStart(start)))
        }

        switch self {
        case .none, .perpetual, .inversePerpetual: return nil
        case .weekly: return fridayFrom(start)
        case .biweekly: return utc.date(byAdding: .weekOfYear, value: 1, to: fridayFrom(start))
        case .monthly: return currentMonthly()
        case .bimonthly: return lastFriday(of: adding(months: 1, to: monthStart(currentMonthly())))
        case .quarterly: return currentQuarterly()
        case .biquarterly: return quarterEnd(adding(months: 3, to: monthStart(currentQuarterly())))
        }
    }
}

/// Handelspaar einer Börse; `pairId` ist deren eigene Kennung (z. B. "BTCUSDT").
struct ExchangePair: Codable, Hashable, Comparable, Sendable, CustomStringConvertible {
    var base: String
    var quote: String
    var pairId: String?
    var contractType: FuturesContractType = .none
    /// Kontrakt auf etwas anderes als einen Krypto-Token: Aktie, Rohstoff (etwa Gold), Devisen oder eine Firma
    /// vor dem Börsengang (Binance «TradFi-Perpetuals»). Ob die Auswahl solche Paare zeigt, regelt «Token &
    /// TradFi» unter Einstellungen › Merkliste. nil: Die Börse sagt dazu nichts (zählt als Krypto). Wie `ExchangePair.tradFi` auf Android.
    var tradFi: Bool?

    init(_ base: String, _ quote: String, _ pairId: String?, _ contractType: FuturesContractType = .none,
         tradFi: Bool? = nil) {
        self.pairId = pairId
        self.base = base
        self.quote = quote
        self.tradFi = tradFi
        self.contractType = contractType
    }

    /// Kein Krypto-Token (siehe `tradFi`).
    var isTradFi: Bool { tradFi ?? false }

    var baseLower: String { base.lowercased() }
    var quoteLower: String { quote.lowercased() }

    /// Basis, dann Gegenwert (beide ohne Gross-/Kleinschreibung), dann Vertragsart.
    static func < (lhs: Self, rhs: Self) -> Bool {
        let byBase = lhs.base.caseInsensitiveCompare(rhs.base)
        guard byBase == .orderedSame else { return byBase == .orderedAscending }
        let byQuote = lhs.quote.caseInsensitiveCompare(rhs.quote)
        guard byQuote == .orderedSame else { return byQuote == .orderedAscending }
        return lhs.contractType < rhs.contractType
    }

    var description: String {
        if let pairId { return pairId }
        guard let suffix = contractType.shortName else { return "\(base):\(quote)" }
        return "\(base):\(quote):\(suffix)"
    }
}

/// Auf Android heisst das Paar beim Abruf `PairQuery` — hier derselbe Typ.
typealias PairQuery = ExchangePair

/// Kurswerte eines Paars; `missing` (-1) bedeutet «nicht geliefert».
struct Ticker: Sendable, Codable, Equatable {
    static let missing: Double = -1

    var bid: Double = Ticker.missing
    var ask: Double = Ticker.missing
    var vol: Double = Ticker.missing
    var volQuote: Double = Ticker.missing
    var high: Double = Ticker.missing
    var low: Double = Ticker.missing
    var last: Double = Ticker.missing
    /// Millisekunden seit 1970, 0 = unbekannt.
    var timestamp: Int64 = 0
    /// Gleitende 24-h-Veränderung in Prozent (1.5 = +1,5 %), so wie die Börse sie im Ticker mitschickt; nil,
    /// wenn sie keinen gleitenden Wert hat — dann rechnet die App selbst aus Stundenkerzen. Tageswerte ab
    /// Mitternacht (UTC, KST …) gehören nicht hierher. Siehe DEVELOPMENT.md, «24 h change».
    var change24hPercent: Double? = nil
    /// Zeit laut Börse (ms); nil, wenn die Börse keine liefert (Kraken, Gate.io, Hyperliquid …). `timestamp`
    /// ist dann der Abruf; bei lange gleichem Kurs zeigt die Merkliste «Kurs unverändert seit …»
    /// (`PriceFreshness`). Gesetzt in `Exchange.readTicker`. Wie Android.
    var exchangeTime: Int64? = nil
    /// Liquidität des Pools in USD — nur DEX-Pools (`DexScreener`); nil = unbekannt bzw. keine DEX-Quelle. Ein dünner
    /// Pool lässt sich mit kleinen Beträgen bewegen: dann kein Alarm. Wie Android `Ticker.liquidityUsd`.
    var liquidityUsd: Double? = nil
    /// `last` ist der Mark-Preis, weil die Börse keinen Mittelkurs schickte (dünnes oder leeres Orderbuch, Hyperliquid).
    /// Die App nennt ihn «Mark» und lässt Kurs-Alarme darauf nicht auslösen (indexbasierter Wert, kein handelbarer Kurs).
    /// Wie Android `Ticker.markFallback`.
    var markFallback: Bool = false
}

/// Wandelt die 24-h-Angaben der Börsen in Prozent (1.5 = +1,5 %) um, wie `Change24h` auf Android. Nur für
/// GLEITENDE 24-h-Werte gedacht; Tageswerte ab Mitternacht (Kraken «o», Upbit/Bithumb «signed_change_rate»)
/// bleiben aussen vor. Fehlende oder unbrauchbare Werte (NaN, ∞, Eröffnung ≤ 0) ergeben nil — beachte, dass
/// `optDouble(_:)` für ein fehlendes Feld NaN liefert.
enum Change24h {
    /// Eine Stunde in ms, der Abstand zweier Stundenkerzen.
    static let hourMillis: Int64 = 3_600_000

    /// Die Börse liefert schon Prozent (Binance «priceChangePercent»).
    static func percent(_ value: Double) -> Double? {
        guard value.isFinite else { return nil }
        return value
    }

    /// Die Börse liefert einen Bruchteil, 0.015 = +1,5 % (Bybit «price24hPcnt»).
    static func fraction(_ value: Double) -> Double? {
        guard value.isFinite else { return nil }
        return value * 100
    }

    /// Aus dem Kurs von vor 24 Stunden (OKX «open24h»).
    static func fromOpen(last: Double, open: Double) -> Double? {
        let valid = [last, open].allSatisfy { $0.isFinite && $0 > 0 }
        return valid ? (last - open) / open * 100 : nil
    }

    /// Aus der absoluten Veränderung über 24 Stunden; die Eröffnung ist dann letzter Kurs minus Veränderung.
    static func fromAbsolute(last: Double, change: Double) -> Double? {
        change.isFinite ? fromOpen(last: last, open: last - change) : nil
    }

    /// Prüft, ob die jüngsten `hours` Stundenkerzen (Startzeiten in ms, Reihenfolge egal, Doppelte zählen einmal)
    /// lückenlos im Stundentakt liegen, und gibt dann die Startzeit der ältesten davon zurück — deren Eröffnung
    /// entspricht ungefähr dem Kurs vor 24 h. nil bei zu wenigen Kerzen oder einer Lücke: Dann ist das Fenster
    /// unvollständig und es gibt keine Veränderung (WOO X Spot). Wie Android.
    static func hourlyWindowStart(_ startTimes: [Int64], hours: Int = 24) -> Int64? {
        guard hours > 0 else { return nil }
        let window = Set(startTimes).sorted(by: >).prefix(hours)
        guard window.count == hours else { return nil }
        let steady = zip(window, window.dropFirst()).allSatisfy { newer, older in newer - older == hourMillis }
        return steady ? window.last : nil
    }
}

/// Inhalt (und zusätzliche Kopfzeilen) einer POST-Anfrage.
struct PostRequest: Sendable {
    var body: String
    var headers: [String: String]? = nil
}

enum EpochTime {
    static let millisInSecond: Int64 = 1000
    static let millisInMinute: Int64 = millisInSecond * 60
    static let millisInHour: Int64 = millisInMinute * 60
    static let millisInDay: Int64 = millisInHour * 24
    static let millisInYear: Int64 = millisInDay * 365

    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Fällig, wenn seit `last` mindestens `minInterval` vergangen ist. Liegt `last` in der Zukunft
    /// (Uhr war vorgestellt und wurde zurückgestellt), gilt die Prüfung ebenfalls als fällig — sonst
    /// bliebe sie bis zu jenem Zeitpunkt stumm. Wie `isDue` in `GasAlertChecker`/`DepegAlarmChecker` (Android).
    static func isIntervalDue(now: Int64, last: Int64, minInterval: Int64) -> Bool {
        let elapsed = now - last
        return elapsed < 0 || elapsed >= minInterval
    }

    /// Zeitangabe unbekannter Einheit → ms, geschätzt an der Grösse: Sekunden, ms, µs oder ns. Wie Android.
    static func parseTimeToMillis(_ time: Int64) -> Int64 {
        // Kleiner als ein Jahr in ms: das waren Sekunden
        guard time >= millisInYear else { return time * millisInSecond }
        // Zu gross für ms: so oft durch 1000, bis es passt (µs einmal, ns wie Phemex «timestamp» zweimal)
        var value = time
        let ceiling = millisInYear * 5000
        while value > ceiling { value /= 1000 }
        return value
    }

    /// ISO-8601-Zeitpunkt → ms (auf ganze Sekunden), 0, wenn er sich nicht lesen lässt.
    static func isoToMillis(_ s: String) -> Int64 {
        guard let date = IsoDates.date(s) else { return 0 }
        return Int64(date.timeIntervalSince1970) * 1000
    }
}
