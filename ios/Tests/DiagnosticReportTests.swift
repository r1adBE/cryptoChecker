import XCTest
@testable import CryptoChecker

/// «Diagnose teilen»: Inhalt des Berichts und vor allem, was nie hineinkommt. Gegenstück: `DiagnosticReportTest.kt`.
final class DiagnosticReportTests: XCTestCase {
    private typealias S = TestSupport

    private let now = TestSupport.t("2026-10-18T12:00:00Z")
    private let evmAddress = "0x6982508145454ce325ddbe47a25d4ec3d2311933"

    private func entry(_ pair: String, _ outcome: AlarmLogOutcome, at: Int64, until: Int64 = 0) -> AlarmLogEntry {
        AlarmLogEntry(alarmId: 7, at: at, pair: pair, exchange: "Kraken", quote: "CHF", price: 64321.987,
                      condition: .PRICE_ABOVE, threshold: 70123.456, currency: "EUR", shownPrice: 61234.5,
                      outcome: outcome, exchangeTime: at - 1, notable: true, until: until)
    }

    private func input(events: [AlarmLogEntry] = [], runs: [CheckRun] = [],
                       pauses: [String: ExchangeBackoff.State] = [:], errorLog: [String]? = nil) -> DiagnosticInput {
        DiagnosticInput(
            createdAt: now, zone: "Europe/Zurich", appVersion: "16.2.2", appBuild: "81", system: "iOS 18.1",
            device: "iPhone16,1", locale: "de-CH",
            settings: DiagnosticSettings(updateMode: "SPARSAM", backgroundIntervalMinutes: 15, exactChecks: nil,
                                         priceNotifications: true, alarmSignal: "SYSTEM", alarmCooldownMinutes: 5,
                                         quietHoursEnabled: true, quietHoursStart: 23 * 60, quietHoursEnd: 7 * 60,
                                         activeAlarms: 3),
            readiness: [AlarmReadiness.Item(row: .notifications, state: .ok, detail: .allowed),
                        AlarmReadiness.Item(row: .backgroundRefresh, state: .warn, detail: .off, action: .appSettings)],
            checkRuns: runs, alarmEvents: events, exchangePauses: pauses, errorLog: errorLog)
    }

    func testHeaderSettingsAndSections() {
        var binance = ExchangeBackoff.State()
        binance.strikes = 2
        binance.pausedUntil = now + 60_000
        binance.reason = .RATE_LIMIT
        var kraken = ExchangeBackoff.State()
        kraken.strikes = 1
        kraken.pausedUntil = now - 60_000
        kraken.reason = .TIMEOUT
        let text = DiagnosticReport.build(input(
            events: [entry("BTC/CHF", .FIRED, at: now - 60_000)],
            runs: [CheckRun(at: now - 120_000, trigger: .bgTask, result: .ok, durationMillis: 2_140)],
            pauses: ["binance": binance, "kraken": kraken]))
        XCTAssertTrue(text.contains("Created: 2026-10-18 14:00 (Europe/Zurich)"))
        XCTAssertTrue(text.contains("App: 16.2.2 (81)"))
        // Seit Build 103: ohne Angabe «local»
        XCTAssertTrue(text.contains("Source: local"))
        XCTAssertFalse(text.contains("Exact checks"))
        XCTAssertTrue(text.contains("Quiet hours: on 23:00-07:00"))
        XCTAssertTrue(text.contains("background_refresh: warn (off)"))
        XCTAssertTrue(text.contains("2026-10-18 13:58 | bgtask | ok | 2.1 s"))
        XCTAssertTrue(text.contains("2026-10-18 13:59 | BTC/CHF | Kraken | PRICE_ABOVE | fired"))
        XCTAssertTrue(text.contains("binance | paused until 2026-10-18 14:01 | RATE_LIMIT | strikes 2"))
        XCTAssertTrue(text.contains("kraken | not paused | TIMEOUT | strikes 1"))
        XCTAssertTrue(text.contains("none - the app keeps no error log"))
        // Seit Build 104: Zähler der Fehler im Hintergrund
        XCTAssertTrue(text.contains("none - the app keeps no error log\nBackground errors: none\n"))
        XCTAssertTrue(text.hasSuffix("\n"))
    }

    func testBackgroundErrorsLine() {
        var full = input()
        full.backgroundErrors = BackgroundErrorSummary(counts: ["refresh": 2, "logos": 1], lastAt: now)
        XCTAssertTrue(DiagnosticReport.build(full).contains("Background errors: refresh 2, logos 1 (last 2026-10-18 12:00 UTC)\n"))
    }

    func testEmptySectionsSayNone() {
        let text = DiagnosticReport.build(input(errorLog: []))
        XCTAssertEqual(text.components(separatedBy: "(none)").count - 1, 4)
    }

    func testCollapsedEventShowsPeriod() {
        let text = DiagnosticReport.build(input(events: [entry("ETH/CHF", .QUIET_HOURS, at: now - 3_600_000,
                                                                  until: now - 600_000)]))
        XCTAssertTrue(text.contains("2026-10-18 13:00 - 2026-10-18 13:50 | ETH/CHF | Kraken | PRICE_ABOVE | quiet_hours"))
    }

    func testNeverPricesThresholdsOrAddresses() {
        var pause = ExchangeBackoff.State()
        pause.strikes = 1
        let text = DiagnosticReport.build(input(
            events: [entry("PEPE/\(evmAddress)", .FIRED, at: now - 60_000), entry("BTC/CHF", .COOLDOWN, at: now - 120_000)],
            pauses: ["bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq": pause],
            errorLog: ["lookup failed for xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"]))
        for number in ["64321", "64’321", "64,321", "70123", "70’123", "70,123", "61234", "61’234"] {
            XCTAssertFalse(text.contains(number), number)
        }
        XCTAssertFalse(text.contains(evmAddress))
        XCTAssertFalse(text.contains("6982508145454ce325ddbe47a25d4ec3d2311933"))
        XCTAssertFalse(text.contains("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        XCTAssertFalse(text.contains("xpub6CUGRU"))
        XCTAssertTrue(text.contains("PEPE/\(DiagnosticReport.redacted)"))
        // Die Eingabe hat gar keine Felder für Portfolio, Beträge, Adressen oder Notizen
        let labels = Mirror(reflecting: input()).children.compactMap { $0.label?.lowercased() }
            + Mirror(reflecting: input().settings).children.compactMap { $0.label?.lowercased() }
        for word in ["portfolio", "amount", "address", "note", "holding", "balance", "wallet", "xpub"] {
            XCTAssertFalse(labels.contains { $0.contains(word) }, word)
        }
    }

    func testRedactSharedCases() throws {
        let data = try S.fixture("diagnostic_redact")
        XCTAssertEqual(data["redacted"] as? String, DiagnosticReport.redacted)
        for c in S.list(data["redact"]) {
            let text = c["in"] as? String ?? ""
            XCTAssertEqual(DiagnosticReport.redact(text), c["out"] as? String, text)
        }
    }

    /// Seit Build 103: Quell-Commit in der Zeile «Source», gleiche Fälle wie Android.
    func testSourceCommitSharedCases() throws {
        let data = try S.fixture("diagnostic_redact")
        for c in S.list(data["sourceCommit"]) {
            let text = c["in"] as? String ?? ""
            XCTAssertEqual(DiagnosticReport.sourceCommit(text), c["out"] as? String, text)
        }
        // Ein Hash wird nie als Adresse ersetzt
        var full = input()
        full.sourceCommit = "3f9a1c2e7b4d5a6f8e9d0c1b2a3f4e5d6c7b8a9f"
        XCTAssertTrue(DiagnosticReport.build(full).contains("Source: 3f9a1c2e7b4d\n"))
    }
}
