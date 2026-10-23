import XCTest
@testable import CryptoChecker

/// Funding-/Open-Interest-Alarme an `PriceRefresher.checkAlarms`: Fehlt der benötigte Wert, heisst es «nicht geprüft»
/// (Grund `noData`) statt stiller Lücke — wie Android `checkDerivativesAlarm` (`run.unchecked(alarm, NO_DATA)`).
final class RefreshEffectsDerivativesTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000

    private func check(_ alarms: [Alarm], values: DerivativesAlarmData.Values?) -> PriceRefresher.Outcome {
        let before = Watch(id: 1, marketKey: "BinanceFutures", marketName: "Test", baseAsset: "BTC", quoteAsset: "USDT",
                           lastPrice: 99)
        var updated = before
        updated.lastPrice = 101
        var inputs = PriceRefresher.AlarmInputs()
        if let values { inputs.derivatives[1] = values }
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(alarms, watch: before, updated: updated, price: 101, now: now,
                                       settings: AppSettings(), speechAllowed: false, inputs: inputs, outcome: &outcome)
        return outcome
    }

    func testFundingOhneWertIstNichtGeprueft() {
        let alarm = Alarm(id: 90_191, watchId: 1, condition: .FUNDING_ABOVE, threshold: 0.01)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        // Open Interest da, Funding fehlt
        let outcome = check([alarm], values: DerivativesAlarmData.Values(fundingPercent: nil, oiUnits: 5, fetchedAt: now))
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarm.id)?.reason, .noData)
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarm.id)?.since, now)
        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
        XCTAssertNil(outcome.alarms[alarm.id])
    }

    func testOpenInterestOhneWertIstNichtGeprueft() {
        let alarm = Alarm(id: 90_192, watchId: 1, condition: .OI_UP, threshold: 5, windowHours: 4)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        let outcome = check([alarm], values: DerivativesAlarmData.Values(fundingPercent: 0.02, oiUnits: nil, fetchedAt: now))
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarm.id)?.reason, .noData)
        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
    }

    /// Mit Wert: geprüft — ein früherer Hinweis «nicht geprüft» fällt weg.
    func testFundingMitWertLoeschtHinweis() {
        let alarm = Alarm(id: 90_193, watchId: 1, condition: .FUNDING_BELOW, threshold: -0.5)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        AlarmCheckStatus.shared.markUnchecked(alarm.id, reason: .noData, now: now - 60_000)
        _ = check([alarm], values: DerivativesAlarmData.Values(fundingPercent: 0.01, oiUnits: nil, fetchedAt: now))
        XCTAssertNil(AlarmCheckStatus.shared.unchecked(alarm.id))
    }

    /// Paar ohne solche Daten (kein Perpetual einer unterstützten Börse): kein Hinweis — wie Android.
    func testOhneDerivateDatenKeinHinweis() {
        let alarm = Alarm(id: 90_194, watchId: 1, condition: .FUNDING_ABOVE, threshold: 0.01)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        _ = check([alarm], values: nil)
        XCTAssertNil(AlarmCheckStatus.shared.unchecked(alarm.id))
    }
}
