import XCTest
@testable import CryptoChecker

/// Fiat-Alarme an `PriceRefresher.checkAlarms`: Devisenkurs zu alt bzw. USDT/USD unsicher → diesmal nicht prüfen (bleibt
/// scharf, Grund im Protokoll); «USDT = 1 angenommen» am Alarmtext; Volumen-Spike aus fremden Kerzen nennt die Börse —
/// wie Android `RefreshEffectsFiatTest`.
final class RefreshEffectsFiatTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000

    private func watch(lastPrice: Double) -> Watch {
        Watch(id: 1, marketKey: "binance", marketName: "Test", baseAsset: "BTC", quoteAsset: "USDT", lastPrice: lastPrice)
    }

    private func check(_ alarms: [Alarm], inputs: PriceRefresher.AlarmInputs) -> PriceRefresher.Outcome {
        let before = watch(lastPrice: 99)
        var updated = before
        updated.lastPrice = 101
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(alarms, watch: before, updated: updated, price: 101, now: now,
                                       settings: AppSettings(), speechAllowed: false, inputs: inputs, outcome: &outcome)
        return outcome
    }

    func testStaleFxRateSkipsFiatAlarmAndLogsReason() {
        let alarmId: Int64 = 90_091
        defer { AlarmCheckStatus.shared.markChecked(alarmId) }
        let alarm = Alarm(id: alarmId, watchId: 1, condition: .PRICE_ABOVE, threshold: 90, currency: "CHF")
        var inputs = PriceRefresher.AlarmInputs()
        inputs.convertRates["1|CHF"] = 0.9
        inputs.convertSkips["1|CHF"] = .FX_STALE

        let outcome = check([alarm], inputs: inputs)
        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
        XCTAssertNil(outcome.alarms[alarmId], "Alarm bleibt unverändert scharf")
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarmId)?.reason, .noRate)
        XCTAssertEqual(outcome.alarmLog.last?.outcome, .FX_STALE)
        XCTAssertEqual(outcome.alarmLog.last?.notable, true, "fällig, aber zurückgehalten")
    }

    func testUnsureUsdtSkipsFiatAlarm() {
        let alarm = Alarm(id: 90_092, watchId: 1, condition: .PRICE_ABOVE, threshold: 90, currency: "CHF")
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        var inputs = PriceRefresher.AlarmInputs()
        inputs.convertRates["1|CHF"] = 0.9
        inputs.convertSkips["1|CHF"] = .UNSURE

        let outcome = check([alarm], inputs: inputs)
        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
        XCTAssertEqual(outcome.alarmLog.last?.outcome, .FX_UNSURE)
    }

    func testAlarmTextNotesUsdtAssumed() {
        let alarm = Alarm(id: 90_093, watchId: 1, condition: .PRICE_ABOVE, threshold: 90, currency: "CHF")
        var inputs = PriceRefresher.AlarmInputs()
        inputs.convertRates["1|CHF"] = 0.9
        inputs.convertUsdtAssumed.insert("1|CHF")

        let outcome = check([alarm], inputs: inputs)
        XCTAssertEqual(outcome.pendingAlarms.count, 1)
        let text = outcome.pendingAlarms.first?.text ?? ""
        XCTAssertTrue(text.hasSuffix(" · " + L("portfolio_usdt_assumed")), text)
    }

    func testVolumeSpikeFromForeignCandlesNamesExchange() {
        let alarm = Alarm(id: 90_094, watchId: 1, condition: .VOLUME_SPIKE, threshold: 2)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        var inputs = PriceRefresher.AlarmInputs()
        inputs.volumes[1] = VolumeSpike(ratio: 5, candleOpenTime: now - 3_600_000, source: "Binance")

        let outcome = check([alarm], inputs: inputs)
        XCTAssertEqual(outcome.pendingAlarms.count, 1)
        XCTAssertEqual(outcome.pendingAlarms.first?.text,
                       AlarmTexts.withNote(L("notification_volume_spike_text", AlarmTexts.factor(5)), candleSource: "Binance"))
    }

    func testVolumeSpikeWithSourceOnlyForForeignExchange() {
        let kraken = Watch(id: 2, marketKey: "Kraken", marketName: "Kraken", baseAsset: "BTC", quoteAsset: "USD")
        let spike = VolumeSpike(ratio: 3, candleOpenTime: 0)
        XCTAssertEqual(spike.withSource(kraken, provider: "Binance").source, "Binance")
        XCTAssertNil(spike.withSource(kraken, provider: "Kraken").source)
        XCTAssertNil(spike.withSource(kraken, provider: nil).source)
    }

    func testJoinDropsEmptyParts() {
        XCTAssertEqual(AlarmTexts.join("A", [nil, "", "B"]), "A · B")
        XCTAssertEqual(AlarmTexts.withNote("A"), "A")
    }
}
