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
        XCTAssertEqual(S.number(fixture["postedToleranceMillis"]).map { Int64($0) }, AlarmDelivery.postedToleranceMillis)
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let markerAt = S.number(c["markerAt"]).map { Int64($0) }
            // Seit Build 104: «postedAt» statt «shown» — gezeigt nur, wenn ab dem Vermerk zugestellt
            let shown: Bool
            if c.keys.contains("postedAt") {
                shown = AlarmDelivery.shownSinceMarker(postedAt: S.number(c["postedAt"]).map { Int64($0) },
                                                       markerAt: markerAt ?? 0)
            } else {
                shown = c["shown"] as? Bool ?? false
            }
            let actual = AlarmDelivery.alreadyDelivered(markerAt: markerAt, now: Int64(S.number(c["now"]) ?? 0),
                                                        shown: shown)
            XCTAssertEqual(actual, c["expected"] as? Bool, name)
        }
    }

    /// Seit Build 104: Eine ältere Mitteilung desselben Alarms bestätigt nicht — Abklingzeit statt stillem Speichern.
    func testAeltereMitteilungBestaetigtNicht() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        _ = await delivery.deliver(alarmId: 11, now: at, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { false }, mark: { true })
        let marks = LockedBox(0)
        let seen = LockedBox<Int64?>(nil)
        let old = at - 3_600_000
        let result = await delivery.deliver(alarmId: 11, now: at + 60_000, cooldownMinutes: 0, holdAlarms: false,
                                            canShow: { true }, show: { true },
                                            mark: { marks.write { $0 += 1 }; return true },
                                            isShown: { markerAt in
                                                seen.current = markerAt
                                                return AlarmDelivery.shownSinceMarker(postedAt: old, markerAt: markerAt)
                                            })
        XCTAssertFalse(result)
        XCTAssertEqual(seen.current, at)
        XCTAssertEqual(marks.current, 0)
        XCTAssertEqual(delivery.unconfirmedAt(11), at)
    }

    /// Seit Build 104: schon gemeldet, aber nicht mehr speicherbar (inzwischen bearbeitet) → Mitteilung wieder weg.
    func testSchonGemeldetAberNichtGespeichertEntferntMitteilung() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        _ = await delivery.deliver(alarmId: 12, now: at, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { false }, mark: { true })
        let retracted = LockedBox(0)
        let posted = at + 300
        let result = await delivery.deliver(alarmId: 12, now: at + 60_000, cooldownMinutes: 0, holdAlarms: false,
                                            canShow: { true }, show: { true }, mark: { false },
                                            retract: { retracted.write { $0 += 1 } },
                                            isShown: { AlarmDelivery.shownSinceMarker(postedAt: posted, markerAt: $0) })
        XCTAssertFalse(result)
        XCTAssertEqual(retracted.current, 1)
        XCTAssertNil(delivery.unconfirmedAt(12))
    }

    /// Seit Build 104: Vermerke gelöschter bzw. ersetzter Alarme aufräumen.
    func testVermerkeAufraeumen() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        for id: Int64 in [1, 2, 3] {
            _ = await delivery.deliver(alarmId: id, now: at, cooldownMinutes: 0, holdAlarms: false,
                                       canShow: { true }, show: { false }, mark: { true })
        }
        delivery.retain([1, 3])
        XCTAssertNil(delivery.unconfirmedAt(2))
        delivery.remove([3])
        XCTAssertNil(delivery.unconfirmedAt(3))
        XCTAssertEqual(delivery.unconfirmedAt(1), at)
        delivery.removeAll()
        XCTAssertNil(delivery.unconfirmedAt(1))
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
                                            isShown: { _ in true })
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
                                            isShown: { _ in false })
        XCTAssertFalse(result)
        XCTAssertEqual(marks.current, 0)
        XCTAssertEqual(delivery.unconfirmedAt(9), at)
    }
}
