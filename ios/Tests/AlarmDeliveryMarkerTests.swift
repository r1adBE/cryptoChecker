import XCTest
@testable import CryptoChecker

/// Prozess-Ende zwischen Mitteilung und Speichern löst keinen zweiten Alarmton aus
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
            // «postedAt» statt «shown» — gezeigt nur, wenn ab dem Vermerk zugestellt
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

    /// Eine ältere Mitteilung desselben Alarms bestätigt nicht — Abklingzeit statt stillem Speichern.
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

    /// Schon gemeldet, aber nicht mehr speicherbar (inzwischen bearbeitet) → Mitteilung wieder weg.
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

    /// Vermerke gelöschter bzw. ersetzter Alarme aufräumen.
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

    /// Auslösung zum Vermerk schon gespeichert?
    func testGespeichertSeitDemVermerkWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("alarm_delivery_marker")["savedSinceCases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let actual = AlarmDelivery.savedSinceMarker(lastTriggeredAt: Int64(S.number(c["lastTriggeredAt"]) ?? -1),
                                                        markerAt: Int64(S.number(c["markerAt"]) ?? -1))
            XCTAssertEqual(actual, c["expected"] as? Bool, c["name"] as? String ?? "?")
        }
    }

    /// Ganze Läufe von `deliver` — ein liegengebliebener Vermerk einer schon gespeicherten Auslösung
    /// verschluckt die neue nicht; ein Prozess-Ende zwischen Melden und Speichern speichert weiter nur.
    func testMeldenWieGemeinsameTestfaelle() async throws {
        let cases = S.list(try S.fixture("alarm_delivery_marker")["deliverCases"])
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for (index, c) in cases.enumerated() {
            let name = c["name"] as? String ?? "?"
            let delivery = AlarmDelivery(storeKey: "deliver-\(index)", store: defaults)
            // Vermerk wie ein unbestätigter Versuch zu dieser Zeit anlegen
            if let markerAt = S.number(c["markerAt"]).map({ Int64($0) }) {
                _ = await delivery.deliver(alarmId: 1, now: markerAt, cooldownMinutes: 0, holdAlarms: false,
                                           canShow: { true }, show: { false }, mark: { true })
                XCTAssertEqual(delivery.unconfirmedAt(1), markerAt, name)
            }
            let lastTriggeredAt = Int64(S.number(c["lastTriggeredAt"]) ?? 0)
            let shown = c["shown"] as? Bool ?? false
            let shows = LockedBox(0)
            let marks = LockedBox(0)
            let result = await delivery.deliver(
                alarmId: 1, now: Int64(S.number(c["now"]) ?? 0), cooldownMinutes: 0,
                holdAlarms: c["holdAlarms"] as? Bool ?? false, canShow: { true },
                show: { shows.write { $0 += 1 }; return true },
                mark: { marks.write { $0 += 1 }; return true },
                isShown: { _ in shown },
                savedSince: { AlarmDelivery.savedSinceMarker(lastTriggeredAt: lastTriggeredAt, markerAt: $0) })
            XCTAssertEqual(shows.current > 0, c["expectShow"] as? Bool, "\(name): show")
            XCTAssertEqual(marks.current > 0, c["expectMark"] as? Bool, "\(name): mark")
            XCTAssertEqual(delivery.unconfirmedAt(1), S.number(c["expectMarkerAfter"]).map { Int64($0) },
                           "\(name): Vermerk danach")
            XCTAssertEqual(result, c["expectResult"] as? Bool, "\(name): Ergebnis")
        }
    }

    /// Vermerke nur löschen/kürzen, wenn der Stand dieses Laufs bleibt.
    func testZuruecksetzenWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("alarm_delivery_marker")["resetCases"])
        XCTAssertGreaterThanOrEqual(cases.count, 4)
        for c in cases {
            let actual = AlarmDelivery.shouldClear(storedKept: c["storedKept"] as? Bool ?? false,
                                                   volatileCopy: c["volatileCopy"] as? Bool ?? false)
            XCTAssertEqual(actual, c["expected"] as? Bool, c["name"] as? String ?? "?")
        }
    }

    /// Übernommen, aber nicht auf die Platte geschrieben → der Vermerk bleibt, die Mitteilung auch
    /// (kein Rückzug); der nächste Prozess meldet darum nicht ein zweites Mal.
    func testNichtGeschriebenBehaeltVermerk() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        let retracted = LockedBox(0)
        let result = await delivery.deliver(alarmId: 13, now: at, cooldownMinutes: 0, holdAlarms: false,
                                            canShow: { true }, show: { true }, mark: { true },
                                            retract: { retracted.write { $0 += 1 } },
                                            persisted: { false })
        XCTAssertTrue(result, "im Speicher gespeichert: zählt als gemeldet")
        XCTAssertEqual(retracted.current, 0)
        XCTAssertEqual(delivery.unconfirmedAt(13), at)
        // Nächster Prozess (Platte noch alt, Mitteilung steht): nur speichern, nicht erneut melden
        let shows = LockedBox(0)
        let again = await delivery.deliver(alarmId: 13, now: at + 60_000, cooldownMinutes: 0, holdAlarms: false,
                                           canShow: { true }, show: { shows.write { $0 += 1 }; return true },
                                           mark: { true }, isShown: { _ in true },
                                           savedSince: { AlarmDelivery.savedSinceMarker(lastTriggeredAt: 0, markerAt: $0) })
        XCTAssertFalse(again)
        XCTAssertEqual(shows.current, 0)
        XCTAssertNil(delivery.unconfirmedAt(13))
    }

    /// Auch im Zweig «nur speichern» bleibt der Vermerk, wenn nicht geschrieben wurde; Gegenprobe
    /// geschrieben → weg.
    func testNurSpeichernOhneSchreibenBehaeltVermerk() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        _ = await delivery.deliver(alarmId: 14, now: at, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { false }, mark: { true })
        _ = await delivery.deliver(alarmId: 14, now: at + 60_000, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { true }, mark: { true }, isShown: { _ in true },
                                   persisted: { false })
        XCTAssertEqual(delivery.unconfirmedAt(14), at)
        _ = await delivery.deliver(alarmId: 14, now: at + 120_000, cooldownMinutes: 0, holdAlarms: false,
                                   canShow: { true }, show: { true }, mark: { true }, isShown: { _ in true },
                                   persisted: { true })
        XCTAssertNil(delivery.unconfirmedAt(14))
    }
}
