import XCTest
@testable import CryptoChecker

/// Seit Build 103 (Review FUN-05): Prozess-Ende zwischen Mitteilung und Speichern löst keinen zweiten Alarmton aus
/// (`testdata/parity/alarm_delivery_marker.json`). Gegenstück: `AlarmDeliveryMarkerParityTest.kt`.
final class AlarmDeliveryMarkerTests: XCTestCase {
    private typealias S = TestSupport
    private let at: Int64 = 1_700_000_000_000
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUp() {
        super.setUp()
        suiteName = "AlarmDeliveryMarkerTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testEntscheidungWieGemeinsameTestfaelle() throws {
        let fixture = try S.fixture("alarm_delivery_marker")
        XCTAssertEqual(S.number(fixture["freshMillis"]).map { Int64($0) }, AlarmDelivery.freshMillis)
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let actual = AlarmDelivery.alreadyDelivered(
                markerAt: S.number(c["markerAt"]).map { Int64($0) },
                now: Int64(S.number(c["now"]) ?? 0),
                shown: c["shown"] as? Bool ?? false)
            XCTAssertEqual(actual, c["expected"] as? Bool, name)
        }
    }

    /// Vermerk steht noch (voriger Lauf endete vor dem Speichern) und die Mitteilung ist da: nur speichern.
    func testFrischerVermerkMitSichtbarerMitteilungSpeichertNur() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        // Lauf 1: Vermerk bleibt stehen (Mitteilung nicht bestätigt — wie ein Prozess-Ende vor dem Speichern)
        _ = await delivery.deliver(alarmId: 8, now: at, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { false }, mark: { true })
        XCTAssertEqual(delivery.unconfirmedAt(8), at)
        // Lauf 2: Mitteilung steht da → nur speichern, nicht erneut melden
        let shows = LockedBox(0)
        let marks = LockedBox(0)
        let result = await delivery.deliver(alarmId: 8, now: at + 60_000, cooldownMinutes: 0, holdAlarms: false,
                                            canShow: { true },
                                            show: { shows.write { $0 += 1 }; return true },
                                            mark: { marks.write { $0 += 1 }; return true },
                                            isShown: { true })
        XCTAssertFalse(result)
        XCTAssertEqual(shows.current, 0)
        XCTAssertEqual(marks.current, 1)
        XCTAssertNil(delivery.unconfirmedAt(8))
    }

    /// Mitteilung weggewischt: der Vermerk zählt als unbestätigter Versuch (Abklingzeit), kein stilles Speichern.
    func testWeggewischteMitteilungWartetDieAbklingzeit() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        _ = await delivery.deliver(alarmId: 9, now: at, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { false }, mark: { true })
        let marks = LockedBox(0)
        let result = await delivery.deliver(alarmId: 9, now: at + 60_000, cooldownMinutes: 0, holdAlarms: false,
                                            canShow: { true }, show: { true },
                                            mark: { marks.write { $0 += 1 }; return true },
                                            isShown: { false })
        XCTAssertFalse(result)
        XCTAssertEqual(marks.current, 0)
        XCTAssertEqual(delivery.unconfirmedAt(9), at)
    }
}
