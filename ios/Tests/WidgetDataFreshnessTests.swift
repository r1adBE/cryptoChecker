import XCTest
@testable import CryptoChecker

/// Uhrzeit im Kopf des Merkliste-Widgets und gespeicherte Kurse aus demselben Stand —
/// Gegenstück: ListWidgetDataVersionTest.kt (Android).
final class WidgetDataFreshnessTests: XCTestCase {

    func testTimeNewerThanWrittenPricesIsBehind() {
        // App hat «Stand 14:55» gesetzt, die Kurse liegen noch vom vorigen Durchlauf auf der Platte
        XCTAssertTrue(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_000 - 60_000))
    }

    func testPricesWrittenAfterTimeAreCurrent() {
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_050))
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_000))
    }

    func testWidgetOwnRefreshWritesFileThenTime() {
        // Widget-Aktualisierung: erst Datei, Millisekunden später die Uhrzeit — kein Warten
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 1_000_000 - 30))
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000,
                                                      writtenAt: 1_000_000 - WidgetDataFreshness.toleranceMillis))
    }

    func testUnknownNeverBehind() {
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 0, writtenAt: 500))
        XCTAssertFalse(WidgetDataFreshness.dataBehind(refreshAt: 1_000_000, writtenAt: 0))
    }

    // MARK: Takt (`WidgetCadence`, nur iOS)

    /// Normal etwa alle 45 Minuten; Kurse werden beim geplanten Neuladen sicher geholt.
    func testCadenceIsAboutFortyFiveMinutes() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let next = WidgetCadence.nextReload(now: now, wantsQuickRetry: false, lastQuickRetryAt: nil)
        XCTAssertEqual(next.date.timeIntervalSince(now), 45 * 60)
        XCTAssertFalse(next.quickRetry)
        XCTAssertLessThan(WidgetCadence.staleAfterMillis, Int64(WidgetCadence.reloadMinutes) * 60_000)
        XCTAssertGreaterThanOrEqual(WidgetCadence.retryAfterMillis, 20 * 60_000)
    }

    /// Höchstens ein früher Neuversuch je Takt — nicht mehr jede Minute.
    func testAtMostOneQuickRetryPerCadence() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let first = WidgetCadence.nextReload(now: now, wantsQuickRetry: true, lastQuickRetryAt: nil)
        XCTAssertTrue(first.quickRetry)
        XCTAssertEqual(first.date.timeIntervalSince(now), 60)
        // Eine Minute später wieder gewünscht: normaler Takt
        let again = now.addingTimeInterval(60)
        let second = WidgetCadence.nextReload(now: again, wantsQuickRetry: true, lastQuickRetryAt: now)
        XCTAssertFalse(second.quickRetry)
        XCTAssertEqual(second.date.timeIntervalSince(again), 45 * 60)
        // Ein Takt später darf es wieder einen geben; Uhr zurückgestellt ebenso
        XCTAssertTrue(WidgetCadence.nextReload(now: now.addingTimeInterval(45 * 60), wantsQuickRetry: true,
                                               lastQuickRetryAt: now).quickRetry)
        XCTAssertTrue(WidgetCadence.nextReload(now: now.addingTimeInterval(-60), wantsQuickRetry: true,
                                               lastQuickRetryAt: now).quickRetry)
    }

    /// Tagesgrenze erreicht: kein früher Neuversuch mehr, nur der normale Takt.
    func testNoQuickRetryAboveDailySoftCap() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let cap = WidgetCadence.dailyReloadSoftCap
        XCTAssertTrue(WidgetCadence.nextReload(now: now, wantsQuickRetry: true, lastQuickRetryAt: nil,
                                               reloadsToday: cap - 1).quickRetry)
        let capped = WidgetCadence.nextReload(now: now, wantsQuickRetry: true, lastQuickRetryAt: nil, reloadsToday: cap)
        XCTAssertFalse(capped.quickRetry)
        XCTAssertEqual(capped.date.timeIntervalSince(now), Double(WidgetCadence.reloadMinutes) * 60)
    }

    /// Zähler je Kalendertag: zählt hoch, beginnt am nächsten Tag bei 0.
    func testDailyReloadCounterResetsPerDay() throws {
        let suite = "WidgetCadenceTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let counter = DailyReloadCounter(defaults: defaults)
        let evening = Date(timeIntervalSince1970: 1_700_000_000) // 2023-11-14 22:13 UTC
        XCTAssertEqual(counter.count(now: evening, timeZone: utc), 0)
        XCTAssertEqual(counter.increment(now: evening, timeZone: utc), 1)
        XCTAssertEqual(counter.increment(by: 2, now: evening, timeZone: utc), 3)
        XCTAssertEqual(counter.count(now: evening, timeZone: utc), 3)
        let nextDay = evening.addingTimeInterval(2 * 3_600)
        XCTAssertEqual(counter.count(now: nextDay, timeZone: utc), 0)
        XCTAssertEqual(counter.increment(now: nextDay, timeZone: utc), 1)
    }

    // MARK: Chart-Zwischenspeicher (`WidgetSparkCache`)

    /// Nur `widget_spark_*` und nur Alte (oder ohne Zeit, oder aus der Zukunft) weg; das Portfolio-Widget liest
    /// die Tageskerzen bis `PortfolioWidgetSeries.keepMillis` — die bleiben.
    func testSparkPruneSelectsOnlyOldSparkKeys() {
        let now: Int64 = 1_700_000_000_000
        let hour: Int64 = 3_600_000
        let entries: [String: Any] = [
            "widget_spark_BTCUSDT|day": ["t": Double(now - 2 * hour)],
            "widget_spark_ETHUSDT|day": ["t": Double(now - PortfolioWidgetSeries.keepMillis - 1)],
            "widget_spark_own_x|week": ["t": Double(now + hour)],
            "widget_spark_broken": "kaputt",
            "widget_spark_prune_at": Double(now - 100 * hour),
            "other_key": ["t": 1.0],
        ]
        XCTAssertEqual(WidgetSparkCache.staleKeys(in: entries, now: now),
                       ["widget_spark_ETHUSDT|day", "widget_spark_broken", "widget_spark_own_x|week"])
    }

    /// Höchstens einmal je Tag aufräumen.
    func testSparkPruneRunsOncePerDay() throws {
        let suite = "WidgetSparkCacheTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let now: Int64 = 1_700_000_000_000
        defaults.set(["t": Double(now - 30 * 3_600_000)], forKey: "widget_spark_OLD|day")
        XCTAssertEqual(WidgetSparkCache.pruneIfDue(defaults: defaults, now: now), 1)
        XCTAssertNil(defaults.object(forKey: "widget_spark_OLD|day"))
        defaults.set(["t": Double(now - 30 * 3_600_000)], forKey: "widget_spark_OLD2|day")
        XCTAssertEqual(WidgetSparkCache.pruneIfDue(defaults: defaults, now: now + 3_600_000), 0)
        XCTAssertNotNil(defaults.object(forKey: "widget_spark_OLD2|day"))
        XCTAssertEqual(WidgetSparkCache.pruneIfDue(defaults: defaults, now: now + WidgetSparkCache.pruneIntervalMillis), 1)
    }
}
