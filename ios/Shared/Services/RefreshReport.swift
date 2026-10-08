import Foundation

// «Letzte Aktualisierung»: was der letzte volle Durchlauf geschafft hat und wo die Zeit
// hingegangen ist — wie `RefreshReport.kt`. Baut `PriceRefresher`, gespeichert in
// `SharedStorage.lastRefreshReport` (JSON), angezeigt im Blatt der Merkliste.

struct RefreshReport: Codable, Sendable, Equatable {
    /// Zeitpunkt des Durchlaufs (Ende), für «vor 8 Min.».
    var at: Int64
    var totalMillis: Int64
    var pairs: Int
    var networkMillis: Int64
    var markets: [MarketRefresh]
    /// nil: auf dieser Plattform nicht gemessen (Zeile entfällt).
    var dbMillis: Int64? = nil
    var effectsMillis: Int64? = nil
    var alarms = 0
    var notifications = 0
    var widgetMillis: Int64? = nil
    /// Wartezeit zwischen Knopfdruck und Start; nil = keine.
    var waitMillis: Int64? = nil
    /// Durchlauf abgebrochen: kurze technische Ursache.
    var aborted: String? = nil
}

/// Ergebnis einer Börse; `updated` + `notTraded` + `failed` = `pairs`.
struct MarketRefresh: Codable, Sendable, Equatable {
    var name: String
    var millis: Int64
    var pairs: Int
    var updated: Int
    var notTraded = 0
    var failed = 0
    var bulkTried = false
    var bulkMillis: Int64 = 0
    /// Kurse der Sammelabfrage; 0 bei versuchter Abfrage = fehlgeschlagen.
    var bulkPrices = 0
    var singles = 0
    /// Häufigste Ursache der Fehler; nil ohne Fehler.
    var reason: RefreshFailure? = nil
    /// Börse pausiert (`ExchangeBackoff`) bis dahin: übersprungen oder gerade in die Pause
    /// geschickt; nil = keine Pause. Ihre Paare behalten den letzten Kurs.
    var pausedUntil: Int64? = nil
    /// Grund der Pause: `.RATE_LIMIT` oder `.TIMEOUT`.
    var pauseReason: RefreshFailure? = nil
}

/// Grün / Orange / Rot (Reihenfolge = Schwere, wie `ordinal` in Kotlin).
enum RefreshStatus: Int, Sendable, Comparable {
    case ok = 0, partial, failed

    static func < (a: RefreshStatus, b: RefreshStatus) -> Bool { a.rawValue < b.rawValue }
}

/// Kurze Fehlerursache für «2 Fehler (Zeitüberschreitung)»; Reihenfolge wie in Kotlin.
enum RefreshFailure: String, Codable, Sendable, CaseIterable {
    case TIMEOUT, OFFLINE, RATE_LIMIT, SERVER, NO_DATA, UNAVAILABLE, OTHER

    var order: Int { RefreshFailure.allCases.firstIndex(of: self) ?? 0 }
}

/// Grosse Statuszeile oben im Blatt.
enum RefreshHeadline: Equatable, Sendable {
    case allUpdated
    case pairsNotUpdated(Int)
    case marketUnreachable(String)
    case marketsUnreachable(Int)
    case aborted
}

enum RefreshReportLogic {

    /// Alle Paare aktualisiert → grün; kein einziges und mindestens ein Fehler → rot;
    /// sonst orange. Nicht mehr gehandelte Paare: höchstens orange.
    static func status(_ market: MarketRefresh) -> RefreshStatus {
        if market.updated >= market.pairs { return .ok }
        if market.updated == 0 && market.failed > 0 { return .failed }
        return .partial
    }

    /// Schlechtester Zustand aller Börsen; abgebrochen = rot.
    static func overall(_ report: RefreshReport) -> RefreshStatus {
        if report.aborted != nil { return .failed }
        return report.markets.map { status($0) }.max() ?? .ok
    }

    /// Rot zuerst, dann orange, dann grün; innerhalb davon die langsamsten zuerst.
    static func sorted(_ markets: [MarketRefresh]) -> [MarketRefresh] {
        markets.sorted { a, b in
            let sa = status(a), sb = status(b)
            if sa != sb { return sa > sb }
            if a.millis != b.millis { return a.millis > b.millis }
            return a.name < b.name
        }
    }

    static func headline(_ report: RefreshReport) -> RefreshHeadline {
        if report.aborted != nil { return .aborted }
        let failed = report.markets.filter { status($0) == .failed }
        if failed.count == 1, let only = failed.first { return .marketUnreachable(only.name) }
        if failed.count > 1 { return .marketsUnreachable(failed.count) }
        let missing = report.markets.reduce(0) { $0 + max(0, $1.pairs - $1.updated) }
        return missing > 0 ? .pairsNotUpdated(missing) : .allUpdated
    }

    /// Häufigste Ursache der gespeicherten Fehlertexte; bei Gleichstand die frühere Art.
    static func reason(_ errors: [String?]) -> RefreshFailure? {
        mostFrequent(errors.map { classify($0) })
    }

    /// Häufigste Ursache; bei Gleichstand die frühere Art (Reihenfolge von `RefreshFailure`).
    static func mostFrequent(_ failures: [RefreshFailure]) -> RefreshFailure? {
        var counts: [RefreshFailure: Int] = [:]
        for f in failures { counts[f, default: 0] += 1 }
        return counts.sorted { a, b in
            a.value != b.value ? a.value > b.value : a.key.order < b.key.order
        }.first?.key
    }

    /// Fehlertext (iOS: `ConnectionErrors`/`MarketHTTP`; Android: Ausnahme bzw. Kennung) → Ursache.
    static func classify(_ error: String?) -> RefreshFailure {
        let e = (error ?? "").trimmingCharacters(in: .whitespaces)
        let lower = e.lowercased()
        if let code = httpCode(lower) {
            return code == 429 || code == 418 ? .RATE_LIMIT : .SERVER
        }
        if timeoutHints.contains(where: { lower.contains($0) }) { return .TIMEOUT }
        if ConnectionErrors.isOffline(e) || offlineHints.contains(where: { lower.contains($0) }) { return .OFFLINE }
        if noDataErrors.contains(e) { return .NO_DATA }
        if unavailableErrors.contains(e) { return .UNAVAILABLE }
        return .OTHER
    }

    /// iOS `MarketHTTP` («HTTP 429»), Android `HttpMarketError` («HttpCode: 429»).
    private static func httpCode(_ lower: String) -> Int? {
        guard let regex = try? NSRegularExpression(pattern: #"\bhttp(?:code:)?\s*(\d{3})\b"#),
              let match = regex.firstMatch(in: lower, range: NSRange(lower.startIndex..., in: lower)),
              let range = Range(match.range(at: 1), in: lower)
        else { return nil }
        return Int(lower[range])
    }

    private static let timeoutHints = ["timeout", "timed out"]

    private static let offlineHints = [
        "unknownhost", "unable to resolve host", "connectexception", "failed to connect",
        "noroutetohost", "network is unreachable", "connection reset", "connection refused", "ssl", "eof",
    ]

    /// = Android UserFriendlyMarketError.EMPTY_RESPONSE / NO_TICKER_DATA.
    private static let noDataErrors: Set<String> = ["Response data is empty", "Parsed ticker has no data"]

    /// = Android UserFriendlyMarketError.MARKET_UNAVAILABLE und der früher deutsch gespeicherte Text
    /// (iOS erkennt «Börse fehlt» schon beim Abfragen, siehe `PriceRefresher.fetchSingle`).
    private static let unavailableErrors: Set<String> = ["Market unavailable", "Börse nicht verfügbar"]
}
