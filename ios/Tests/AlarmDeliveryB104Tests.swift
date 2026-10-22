import XCTest
@testable import CryptoChecker

/// Seit Build 104 (Review ARC2-02, FUN2-04): Portfolio-Alarme mit eigenem Vermerk-Speicher und einmaliges Ausschalten der
/// «fällt um ≥ 100 %»-Alarme. Gegenstück: `AlarmDeliveryB104Test.kt`.
final class AlarmDeliveryB104Tests: XCTestCase {
    private typealias S = TestSupport
    private let at: Int64 = 1_700_000_000_000
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUp() {
        super.setUp()
        suiteName = "AlarmDeliveryB104Tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    /// Paar- und Portfolio-Alarm mit derselben Id stören sich nicht (eigene Einträge, wie Android eigene Dateien).
    func testPortfolioVermerkGetrenntVonPaarVermerk() async {
        let pairs = AlarmDelivery(storeKey: "alarm_unconfirmed_at", store: defaults)
        let portfolio = AlarmDelivery(storeKey: "portfolio_alarm_unconfirmed_at", store: defaults)
        _ = await portfolio.deliver(alarmId: 5, now: at, cooldownMinutes: 0, holdAlarms: false,
                                    canShow: { true }, show: { false }, mark: { true })
        XCTAssertEqual(portfolio.unconfirmedAt(5), at)
        XCTAssertNil(pairs.unconfirmedAt(5))
        pairs.removeAll()
        XCTAssertEqual(portfolio.unconfirmedAt(5), at)
    }

    /// Portfolio-Alarm: schon gemeldet (Prozess-Ende), aber inzwischen geändert → Mitteilung wieder weg, Vermerk weg.
    func testPortfolioSchonGemeldetNichtGespeichertEntfernt() async {
        let portfolio = AlarmDelivery(storeKey: "portfolio", store: defaults)
        _ = await portfolio.deliver(alarmId: 6, now: at, cooldownMinutes: 0, holdAlarms: false,
                                    canShow: { true }, show: { false }, mark: { true })
        let retracted = LockedBox(0)
        let result = await portfolio.deliver(alarmId: 6, now: at + 30_000, cooldownMinutes: 0, holdAlarms: false,
                                             canShow: { true }, show: { true }, mark: { false },
                                             retract: { retracted.write { $0 += 1 } },
                                             isShown: { AlarmDelivery.shownSinceMarker(postedAt: self.at + 100, markerAt: $0) })
        XCTAssertFalse(result)
        XCTAssertEqual(retracted.current, 1)
        XCTAssertNil(portfolio.unconfirmedAt(6))
    }

    /// Nur eingeschaltete fallende Prozent-Alarme ab 100 % werden ausgeschaltet.
    func testFaelltUmHundertProzentWirdAusgeschaltet() {
        func alarm(_ id: Int64, _ condition: String, _ threshold: Double, enabled: Bool = true) -> Alarm {
            var a = S.alarm(["condition": condition, "threshold": threshold, "enabled": enabled])
            a.id = id
            return a
        }
        let alarms = [
            alarm(1, "CHANGE_PERCENT_DOWN", 100),
            alarm(2, "CHANGE_PERCENT_DOWN", 99.5),
            alarm(3, "OI_DOWN", 150),
            alarm(4, "CHANGE_PERCENT_UP", 150),
            alarm(5, "CHANGE_PERCENT_DOWN", 120, enabled: false),
        ]
        XCTAssertEqual(FallAlarmCleanup.idsToDisable(alarms), [1, 3])
    }
}
