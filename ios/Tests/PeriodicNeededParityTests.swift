import XCTest
@testable import CryptoChecker

/// Bedarf der periodischen Hintergrund-Aktualisierung (seit Build 103): gemeinsame Fälle mit Android
/// (`PeriodicNeededParityTest.kt`, `periodic_needed.json`) und die Eingaben aus Einstellungen und Merkliste.
final class PeriodicNeededParityTests: XCTestCase {
    private func flag(_ object: [String: Any], _ key: String) -> Bool { object[key] as? Bool ?? false }

    func testPeriodicNeededMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("periodic_needed")["periodicNeeded"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let d = try XCTUnwrap(c["demand"] as? [String: Any])
            let demand = BackgroundWorkDemand.WorkDemand(
                pairs: TestSupport.list(d["pairs"]).map {
                    BackgroundWorkDemand.PairWork(disabled: flag($0, "disabled"),
                                                  hasEnabledAlarm: flag($0, "hasEnabledAlarm"),
                                                  notificationEnabled: flag($0, "notificationEnabled"),
                                                  ttsEnabled: flag($0, "ttsEnabled"))
                },
                priceNotifications: flag(d, "priceNotifications"), ttsPrices: flag(d, "ttsPrices"),
                activityAlerts: flag(d, "activityAlerts"), pairWidgets: flag(d, "pairWidgets"),
                portfolioWork: flag(d, "portfolioWork"), marketAlarms: flag(d, "marketAlarms"),
                watchlistKept: flag(d, "watchlistKept"), pulseWidget: flag(d, "pulseWidget"))
            XCTAssertEqual(BackgroundWorkDemand.periodicNeeded(demand), c["expected"] as? Bool, "\(c["name"] ?? "")")
        }
    }

    func testPortfolioWorkMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("periodic_needed")["portfolioWork"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let actual = BackgroundWorkDemand.portfolioWork(portfolioEnabled: flag(c, "portfolioEnabled"),
                                                            alarmOn: flag(c, "alarmOn"),
                                                            portfolioKept: flag(c, "portfolioKept"),
                                                            widget: flag(c, "widget"))
            XCTAssertEqual(actual, c["expected"] as? Bool, "\(c["name"] ?? "")")
        }
    }

    // MARK: Eingaben (wie Android `PriceUpdateScheduler.demand`)

    func testDemandFromSettingsAndSnapshot() {
        var settings = AppSettings()
        settings.priceNotifications = true
        settings.ttsEnabled = true
        settings.ttsAlarmsOnly = true
        var snap = SharedStorage.Snapshot()
        snap.watches = [Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT"),
                        Watch(id: 2, marketKey: "binance", marketName: "Binance", baseAsset: "ETH", quoteAsset: "USDT")]
        snap.watches[1].disabled = true
        snap.alarms = [Alarm(id: 1, watchId: 2, condition: .PRICE_ABOVE, threshold: 1, enabled: true),
                       Alarm(id: 2, watchId: 1, condition: .PRICE_BELOW, threshold: 1, enabled: false)]
        let demand = BackgroundWorkDemand.demand(settings: settings, read: .ok(snap), widgets: .none)
        XCTAssertEqual(demand.pairs.map(\.hasEnabledAlarm), [false, true])
        XCTAssertEqual(demand.pairs.map(\.disabled), [false, true])
        XCTAssertFalse(demand.ttsPrices, "«Nur Alarme»: keine Kursansagen")
        XCTAssertFalse(demand.watchlistKept)
        // Neues Paar ohne Kurs-Mitteilung, Alarm nur am deaktivierten Paar: nichts zu tun
        snap.watches[0].notificationEnabled = false
        XCTAssertFalse(BackgroundWorkDemand.periodicNeeded(
            BackgroundWorkDemand.demand(settings: settings, read: .ok(snap), widgets: .none)))
        // Merkliste-Widget mit aktivem Paar: planen
        XCTAssertTrue(BackgroundWorkDemand.periodicNeeded(BackgroundWorkDemand.demand(
            settings: settings, read: .ok(snap), widgets: BackgroundWorkDemand.WidgetPresence(kinds: ["WatchlistWidget"]))))
    }

    /// Nicht lesbare bzw. nicht zugreifbare Merkliste: geplant lassen; Portfolio-Alarme nur mit Portfolio.
    func testKeptStatesAndPortfolioAlarms() {
        let settings = AppSettings()
        for read in [SharedStorage.SnapshotRead.inaccessible, .unreadable] {
            XCTAssertTrue(BackgroundWorkDemand.periodicNeeded(
                BackgroundWorkDemand.demand(settings: settings, read: read, widgets: .none)))
        }
        XCTAssertFalse(BackgroundWorkDemand.periodicNeeded(
            BackgroundWorkDemand.demand(settings: settings, read: .missing, widgets: .none)))
        var snap = SharedStorage.Snapshot()
        snap.portfolioAlarms = [PortfolioAlarm(id: 1, kind: .VALUE_BELOW, threshold: 100, currency: "CHF")]
        var on = settings
        on.portfolioEnabled = false
        XCTAssertFalse(BackgroundWorkDemand.demand(settings: on, read: .ok(snap), widgets: .none).portfolioWork)
        on.portfolioEnabled = true
        XCTAssertTrue(BackgroundWorkDemand.demand(settings: on, read: .ok(snap), widgets: .none).portfolioWork)
        XCTAssertTrue(BackgroundWorkDemand.demand(settings: on, read: .ok(SharedStorage.Snapshot()), widgets: .none,
                                                  portfolioAlarmsKept: true).portfolioWork)
        XCTAssertEqual(BackgroundWorkDemand.WidgetPresence(kinds: ["PulseWidget", "PortfolioWidget"]),
                       BackgroundWorkDemand.WidgetPresence(pairs: false, portfolio: true, pulse: true))
    }
}
