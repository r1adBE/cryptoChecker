import Foundation

/// Kontrakttyp eines Futures-Paars. Die Zahlenwerte entsprechen der Android-Fassung
/// (wichtig für Sicherungen, die zwischen den Plattformen ausgetauscht werden).
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

    static func < (a: Self, b: Self) -> Bool { a.order < b.order }

    /// Reihenfolge wie die Enum-Reihenfolge in Kotlin.
    private var order: Int { Self.allCases.firstIndex(of: self) ?? 0 }

    /// Name in der Android-Sicherung (Kotlin-Enum-Name).
    var backupName: String {
        switch self {
        case .none: "NONE"
        case .perpetual: "PERPETUAL"
        case .weekly: "WEEKLY"
        case .biweekly: "BIWEEKLY"
        case .monthly: "MONTHLY"
        case .bimonthly: "BIMONTHLY"
        case .quarterly: "QUARTERLY"
        case .biquarterly: "BIQUARTERLY"
        case .inversePerpetual: "INVERSE_PERPETUAL"
        }
    }

    init(backupName: String) {
        self = Self.allCases.first { $0.backupName == backupName } ?? .none
    }

    static func fromInt(_ v: Int) -> Self { Self(rawValue: v) ?? .none }

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

    var isPerpetual: Bool { self == .perpetual || self == .inversePerpetual }

    /// Laufzeit-Kontrakte wechseln ihre Kennung beim Verfall.
    var isRolling: Bool { !(self == .none || isPerpetual) }

    /// Verfallsdatum (UTC) für Laufzeit-Kontrakte, wie in der Android-Fassung berechnet.
    var deliveryDate: Date? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let today = cal.startOfDay(for: Date())

        func endOfWeek(_ d: Date) -> Date {
            // Freitag = 6 im gregorianischen Kalender (Sonntag = 1)
            if cal.component(.weekday, from: d) == 6 { return d }
            return cal.nextDate(after: d, matching: DateComponents(weekday: 6), matchingPolicy: .nextTime) ?? d
        }
        func lastFridayOfMonth(_ d: Date) -> Date {
            let comps = cal.dateComponents([.year, .month], from: d)
            let first = cal.date(from: comps)!
            let nextMonth = cal.date(byAdding: .month, value: 1, to: first)!
            var day = cal.date(byAdding: .day, value: -1, to: nextMonth)!
            while cal.component(.weekday, from: day) != 6 { day = cal.date(byAdding: .day, value: -1, to: day)! }
            return day
        }
        func endOfQuarter(_ d: Date) -> Date {
            let month = cal.component(.month, from: d)
            let firstMonthOfQuarter = ((month - 1) / 3) * 3 + 1
            var comps = cal.dateComponents([.year], from: d)
            comps.month = firstMonthOfQuarter + 2
            comps.day = 1
            return lastFridayOfMonth(cal.date(from: comps)!)
        }

        switch self {
        case .none, .perpetual, .inversePerpetual: return nil
        case .weekly: return endOfWeek(today)
        case .biweekly: return endOfWeek(cal.date(byAdding: .weekOfYear, value: 1, to: today)!)
        case .monthly: return lastFridayOfMonth(today)
        case .bimonthly: return lastFridayOfMonth(cal.date(byAdding: .month, value: 1, to: today)!)
        case .quarterly: return endOfQuarter(today)
        case .biquarterly: return endOfQuarter(cal.date(byAdding: .month, value: 3, to: today)!)
        }
    }
}

/// Ein Handelspaar einer Börse. `pairId` ist die Kennung der Börse (z. B. "BTCUSDT").
struct CurrencyPairInfo: Codable, Hashable, Comparable, Sendable, CustomStringConvertible {
    var base: String
    var quote: String
    var pairId: String?
    var contractType: FuturesContractType = .none

    init(_ base: String, _ quote: String, _ pairId: String?, _ contractType: FuturesContractType = .none) {
        self.base = base
        self.quote = quote
        self.pairId = pairId
        self.contractType = contractType
    }

    var baseLower: String { base.lowercased() }
    var quoteLower: String { quote.lowercased() }

    static func < (a: Self, b: Self) -> Bool {
        let cb = a.base.caseInsensitiveCompare(b.base)
        if cb != .orderedSame { return cb == .orderedAscending }
        let cq = a.quote.caseInsensitiveCompare(b.quote)
        if cq != .orderedSame { return cq == .orderedAscending }
        return a.contractType < b.contractType
    }

    var description: String {
        if let pairId { return pairId }
        let c = contractType.shortName.map { ":\($0)" } ?? ""
        return "\(base):\(quote)\(c)"
    }
}

/// In der Android-Fassung `CheckerInfo` — dasselbe wie ein Paar.
typealias CheckerInfo = CurrencyPairInfo

/// Kursdaten eines Paars. `noData` (-1) heisst: Wert nicht geliefert.
struct Ticker: Sendable, Codable, Equatable {
    static let noData: Double = -1

    var bid: Double = Ticker.noData
    var ask: Double = Ticker.noData
    var vol: Double = Ticker.noData
    var volQuote: Double = Ticker.noData
    var high: Double = Ticker.noData
    var low: Double = Ticker.noData
    var last: Double = Ticker.noData
    /// Millisekunden seit 1970; 0 = unbekannt.
    var timestamp: Int64 = 0
}

/// Body (und Kopfzeilen) für eine POST-Anfrage.
struct PostRequestInfo: Sendable {
    var body: String
    var headers: [String: String]? = nil
}

enum TimeUtils {
    static let millisInSecond: Int64 = 1000
    static let millisInMinute: Int64 = 60 * millisInSecond
    static let millisInHour: Int64 = 60 * millisInMinute
    static let millisInDay: Int64 = 24 * millisInHour
    static let millisInYear: Int64 = 365 * millisInDay

    /// Sekunden, Millisekunden oder Mikrosekunden → Millisekunden.
    static func parseTimeToMillis(_ time: Int64) -> Int64 {
        if time < millisInYear { return time * millisInSecond }
        if time > 5000 * millisInYear { return time / 1000 }
        return time
    }

    /// ISO-8601-Datum → Millisekunden; 0 bei Fehler.
    static func isoToMillis(_ s: String) -> Int64 {
        let f1 = ISO8601DateFormatter()
        f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f1.date(from: s) { return Int64(d.timeIntervalSince1970) * 1000 }
        let f2 = ISO8601DateFormatter()
        if let d = f2.date(from: s) { return Int64(d.timeIntervalSince1970) * 1000 }
        return 0
    }

    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Von Kraken & Co. benutzte Sonderkürzel.
enum VirtualCurrency {
    static let BTC = "BTC", XBT = "XBT", DOGE = "DOGE", XDG = "XDG", VEN = "VEN", XVN = "XVN"
    static let USD = "USD", CNY = "CNY"
}
