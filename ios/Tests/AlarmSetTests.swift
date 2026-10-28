import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus AlarmBellRouteTests

/// Ziel der Glocke im Merklisten-Kopf — gleiche Fälle wie `AlarmBellRouteTest.kt`.
final class AlarmBellRouteTests: XCTestCase {

    func testWithAlarmsAlwaysOverview() {
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 1, watchCount: 0), .overview)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 1, watchCount: 1), .overview)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 7, watchCount: 12), .overview)
    }

    func testNotLoadedYetStaysOnOverview() {
        // Früher Tipp vor dem ersten Stand der Daten: nie ein falscher Sprung
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: nil, watchCount: 0), .overview)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: nil, watchCount: 3), .overview)
    }

    func testWithoutAlarmsLeadsToCreatingOne() {
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 1), .editor)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 2), .pickPair)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 40), .pickPair)
        XCTAssertEqual(AlarmBellRoute.of(alarmCount: 0, watchCount: 0), .addCoin)
    }
}

// ---- aus AlarmCardStatusTests

/// Statuszeile der Alarm-Karte: Reihenfolge der Gründe, Grenzen, ausgeschaltete Alarme. Wie `AlarmCardStatusTest`.
final class AlarmCardStatusTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private let minute: Int64 = 60_000
    private var hour: Int64 { 60 * minute }
    private var stale: Int64 { 45 * minute }

    private func status(
        enabled: Bool = true,
        allowed: Bool = true,
        dex: Bool = false,
        exchangeTime: Int64? = nil,
        lastUpdate: Int64? = nil,
        unchecked: Bool = false
    ) -> AlarmCardStatus.Kind? {
        AlarmCardStatus.of(
            alarmEnabled: enabled, notificationsAllowed: allowed, dexBlocksAlarms: dex,
            exchangeTime: exchangeTime, lastUpdate: lastUpdate ?? (now - minute), now: now,
            staleAfterMillis: stale, unchecked: unchecked
        )
    }

    func testHealthyAlarmHasNoLine() {
        XCTAssertNil(status())
        XCTAssertNil(status(exchangeTime: now - hour))
    }

    func testDisabledAlarmNeverHasALine() {
        XCTAssertNil(status(enabled: false, allowed: false, dex: true, lastUpdate: now - 10 * hour, unchecked: true))
    }

    func testNotificationsOffComesFirst() {
        XCTAssertEqual(.notificationsOff, status(allowed: false, dex: true, lastUpdate: now - 10 * hour, unchecked: true))
    }

    func testLowLiquidityBeforeStaleAndUnchecked() {
        XCTAssertEqual(.lowLiquidity, status(dex: true, exchangeTime: now - 3 * hour, unchecked: true))
    }

    func testExchangeTimeOlderThanTwoHoursWaits() {
        XCTAssertEqual(.waitingForPrice, status(exchangeTime: now - 2 * hour - 1, unchecked: true))
        XCTAssertNil(status(exchangeTime: now - 2 * hour))
    }

    func testFetchOlderThanStaleThresholdWaits() {
        XCTAssertEqual(.waitingForPrice, status(lastUpdate: now - stale - 1))
        XCTAssertNil(status(lastUpdate: now - stale))
        // Noch nie abgerufen: kein «Wartet» (dort steht ohnehin «—»)
        XCTAssertNil(status(lastUpdate: 0))
    }

    func testUncheckedOnlyWhenNothingElseApplies() {
        XCTAssertEqual(.unchecked, status(unchecked: true))
    }
}

// ---- aus AlarmDeliveryB104Tests

/// Review ARC2-02, FUN2-04: Portfolio-Alarme mit eigenem Vermerk-Speicher und einmaliges Ausschalten der
/// «fällt um ≥ 100 %»-Alarme. Gegenstück: `AlarmDeliveryB104Test`.
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
}

// ---- aus AlarmPairPickerTests

/// Review UX-02: Paarwahl für einen neuen Alarm — Suche wie in der Merkliste, Favoriten zuerst
/// (`testdata/parity/alarm_pair_picker.json`). Gegenstück: `AlarmPairPickerParityTest`. Über die
/// vorberechneten Zeilen (`AlarmPairPicker.makeRows`) mit gefalteten Coin-Namen.
@MainActor
final class AlarmPairPickerTests: XCTestCase {
    private typealias S = TestSupport

    func testAuswahlWieGemeinsameTestfaelle() throws {
        let fixture = try S.fixture("alarm_pair_picker")
        let watches: [Watch] = S.list(fixture["watches"]).map { w in
            let exchange = w["exchangeName"] as? String ?? ""
            var watch = Watch(id: Int64(S.number(w["id"]) ?? 0), exchangeKey: exchange.lowercased(), exchangeName: exchange,
                              baseAsset: w["base"] as? String ?? "", quoteAsset: w["quote"] as? String ?? "")
            watch.favorite = w["favorite"] as? Bool ?? false
            watch.note = w["note"] as? String
            return watch
        }
        var names: [Int64: String] = [:]
        for (id, name) in fixture["names"] as? [String: String] ?? [:] {
            if let key = Int64(id) { names[key] = name }
        }
        let rows = AlarmPairPicker.makeRows(watches)
        let folded = AlarmPairPicker.foldedNames(names)
        let cases = S.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let expected = (c["expected"] as? [NSNumber] ?? []).map(\.int64Value)
            let actual = AlarmPairPicker.choices(rows, query: c["query"] as? String ?? "", foldedNames: folded).map(\.id)
            XCTAssertEqual(actual, expected, name)
        }
        // Ein neuer Kurs ändert die Zeilen-Quelle nicht, ein Favorit schon
        let priced = watches.map { w -> Watch in var copy = w; copy.lastPrice = 42; copy.lastUpdate = 1; return copy }
        XCTAssertEqual(watches.map(AlarmPairPicker.Source.init), priced.map(AlarmPairPicker.Source.init))
        let flipped = watches.map { w -> Watch in var copy = w; copy.favorite.toggle(); return copy }
        XCTAssertNotEqual(watches.map(AlarmPairPicker.Source.init), flipped.map(AlarmPairPicker.Source.init))
        // PERF3-R2: Schlüssel für `.task(id:)` ebenso
        XCTAssertEqual(AlarmPairPicker.sourceKey(watches), AlarmPairPicker.sourceKey(priced))
        XCTAssertNotEqual(AlarmPairPicker.sourceKey(watches), AlarmPairPicker.sourceKey(flipped))
        XCTAssertNotEqual(AlarmPairPicker.sourceKey(watches), AlarmPairPicker.sourceKey(Array(watches.reversed())))
    }

    /// PERF3-R2: das Struct neu zu erzeugen (Elternbildschirm zeichnet neu) faltet keine Zeilen.
    func testErzeugenRechnetKeineZeilen() {
        let watches = (1...50).map { Watch(id: Int64($0), exchangeKey: "binance", exchangeName: "Binance", baseAsset: "C\($0)",
                                           quoteAsset: "USDT") }
        let before = AlarmPairPicker.makeRowsCalls
        for _ in 0..<5 { _ = AlarmPairPicker(watches: watches) { _ in } }
        XCTAssertEqual(AlarmPairPicker.makeRowsCalls, before)
    }
}

// ---- aus AlarmPulseTests

/// Wie `AlarmPulseTest.kt`.
final class AlarmPulseTests: XCTestCase {

    func testOnlyNewTriggerWhileOpenPulses() {
        // Erster Stand beim Öffnen: schon vorhandene Auslösungen pulsieren nicht
        XCTAssertFalse(AlarmPulse.isNew(previous: nil, current: 1_000))
        XCTAssertFalse(AlarmPulse.isNew(previous: nil, current: nil))
        // Neue Auslösung
        XCTAssertTrue(AlarmPulse.isNew(previous: 1_000, current: 2_000))
        XCTAssertTrue(AlarmPulse.isNew(previous: 0, current: 2_000))
        // Unverändert, zurückgesetzt oder noch nie
        XCTAssertFalse(AlarmPulse.isNew(previous: 2_000, current: 2_000))
        XCTAssertFalse(AlarmPulse.isNew(previous: 2_000, current: 0))
        XCTAssertFalse(AlarmPulse.isNew(previous: 0, current: 0))
        XCTAssertFalse(AlarmPulse.isNew(previous: 1_000, current: nil))
    }

    func testPulseIsShortAndSubtle() {
        XCTAssertTrue((0.1...0.25).contains(AlarmPulse.seconds))
        XCTAssertTrue(AlarmPulse.scale > 1 && AlarmPulse.scale <= 1.25)
    }
}

// ---- aus AlarmReliabilityTests

/// Zeile über der Merkliste (`Parity/alarm_reliability.json`): gelesener Zustand → ein Satz und Farbe.
/// Gegenstück: `AlarmReliabilityParityTest`.
final class AlarmReliabilityTests: XCTestCase {
    private typealias S = TestSupport

    private func bool(_ m: [String: Any], _ key: String, _ fallback: Bool) -> Bool { m[key] as? Bool ?? fallback }

    private func input(_ m: [String: Any]) throws -> AlarmReadiness.Input {
        var input = AlarmReadiness.Input(
            platform: (m["platform"] as? String) == "ios" ? .ios : .android,
            notifications: try XCTUnwrap(AlarmReadiness.Access(rawValue: m["notifications"] as? String ?? "")),
            now: S.millis(m["now"]),
            minuteOfDay: Int(S.number(m["minuteOfDay"]) ?? 0))
        input.lastBackgroundCheck = S.number(m["lastBackgroundCheck"]).map { Int64($0) }
        // Gespeicherte Läufe («at;trigger;result;dauer»), falls der Fall sie nennt
        if let lines = m["runs"] as? [String] {
            let runs = CheckRunLog.decode(lines.joined(separator: "\n"))
            input.lastBackgroundCheck = CheckRunLog.lastBackgroundCheck(runs)
            input.lastFailedBackgroundCheck = CheckRunLog.lastFailedBackgroundCheck(runs)
        }
        input.storageNoKey = bool(m, "storageNoKey", false)
        input.hasAlarms = bool(m, "hasAlarms", false)
        input.channel = (m["channel"] as? String).flatMap { AlarmReadiness.Channel(rawValue: $0) }
        input.batteryUnrestricted = bool(m, "batteryUnrestricted", false)
        input.backgroundRestricted = bool(m, "backgroundRestricted", false)
        input.backgroundRefresh = bool(m, "backgroundRefresh", true)
        return input
    }

    func testStaleLimitMatches() throws {
        let fixture = try S.fixture("alarm_reliability")
        XCTAssertEqual(AlarmReliability.staleMillis(.android), S.millis(fixture["staleMillis"]))
        XCTAssertEqual(AlarmReliability.staleMillis(.ios), S.millis(fixture["staleMillisIos"]))
        // iOS: dieselbe Grenze wie die Seite «Damit Alarme ankommen»
        XCTAssertEqual(AlarmReliability.staleMillis(.ios), AlarmReadiness.staleCheckMillis)
    }

    func testStatus() throws {
        let cases = S.list(try S.fixture("alarm_reliability")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        // Jeder Satz kommt im gemeinsamen Testfall vor
        XCTAssertEqual(Set(AlarmReliability.Kind.allCases.map(\.rawValue)), Set(cases.compactMap { $0["kind"] as? String }))
        for c in cases {
            let name = c["name"] as? String ?? ""
            let m = c["input"] as? [String: Any] ?? [:]
            let actual = AlarmReliability.status(try input(m), oemVendorKnown: bool(m, "oem", false))
            XCTAssertEqual(actual.kind.rawValue, c["kind"] as? String, name)
            XCTAssertEqual(actual.warn, c["warn"] as? Bool, name)
        }
    }

    /// Zeile nur, wenn etwas nicht stimmt: Normalfall (`.recent`) und `.hidden` ohne Zeile — wie Android.
    func testLineShownOnlyWhenSomethingIsWrong() throws {
        let expected = Set(try XCTUnwrap(S.fixture("alarm_reliability")["lineShownKinds"] as? [String]))
        let actual = Set(AlarmReliability.Kind.allCases.filter { AlarmReliabilityLine.isShown($0) }.map(\.rawValue))
        XCTAssertEqual(actual, expected)
    }
}

// ---- aus AlarmToggleParityTests

/// Review FUN3-02/UX3-06: «fällt um ≥ 100 %» lässt sich per Schalter nicht einschalten
/// (`Parity/alarm_toggle.json`) — wie `AlarmToggleParityTest`.
final class AlarmToggleParityTests: XCTestCase {
    func testToggleMatchesFixture() throws {
        let cases = TestSupport.list(try TestSupport.fixture("alarm_toggle")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let condition = try XCTUnwrap(AlarmCondition(rawValue: c["condition"] as? String ?? ""), name)
            let threshold = (c["threshold"] as? NSNumber)?.doubleValue ?? .nan
            XCTAssertEqual(BackupManager.toggleAllowed(condition, threshold, enable: c["enable"] as? Bool ?? false),
                           c["allowed"] as? Bool, name)
        }
    }

    /// Schalter (Karte, Übersicht) und Schreibweg nutzen dieselbe Regel.
    func testSwitchesAndWritePathUseTheRule() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App")
        func text(_ path: String) throws -> String {
            try String(contentsOf: app.appendingPathComponent(path), encoding: .utf8)
        }
        XCTAssertTrue(try text("Features/Alarms/AlarmCard.swift").contains("enabled: BackupManager.toggleAllowed("))
        XCTAssertTrue(try text("Features/Alarms/AlarmsOverviewScreen.swift").contains("enabled: BackupManager.toggleAllowed("))
        let alarms = try text("Services/AppData+Alarms.swift")
        let toggle = alarms.components(separatedBy: "func setAlarmEnabled(").last ?? ""
        XCTAssertTrue(toggle.components(separatedBy: "mutate(").first?.contains("BackupManager.toggleAllowed(") == true)
    }
}
