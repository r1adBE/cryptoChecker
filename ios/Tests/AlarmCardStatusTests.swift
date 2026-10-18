import XCTest
@testable import CryptoChecker

/// Statuszeile der Alarm-Karte: Reihenfolge der Gründe, Grenzen, ausgeschaltete Alarme. Wie `AlarmCardStatusTest.kt`.
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
