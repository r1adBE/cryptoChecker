import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle des Volumen-Spikes (`Parity/volume_spike.json`): Stundenraster, laufende Stunde
/// aus Uhr und neuester Kerze, fehlende Stunden = 0, veraltete Reihe → nichts — wie `VolumeSpikeParityTest.kt`.
final class VolumeSpikeParityTests: XCTestCase {

    private let hour: Int64 = 3_600_000

    func testSpikeOf() throws {
        let data = try TestSupport.fixture("volume_spike")
        let running = Int64(try XCTUnwrap(TestSupport.number(data["runningHour"])))
        let cases = TestSupport.list(data["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let now = running * hour + Int64(TestSupport.number(c["nowMinute"]) ?? 0) * 60_000
            let candles: [MarketCandle] = (c["candles"] as? [[Any]] ?? []).map { k in
                let minute = k.count > 2 ? Int64(TestSupport.number(k[2]) ?? 0) : 0
                let openTime = (running + Int64(TestSupport.number(k[0]) ?? 0)) * hour + minute * 60_000
                return MarketCandle(openTime: openTime, open: 1, high: 1, low: 1, close: 1,
                                    volume: TestSupport.number(k[1]) ?? .nan)
            }
            let spike = VolumeDataSource.spikeOf(candles, now: now)
            guard let expected = c["expected"] as? [String: Any] else {
                XCTAssertNil(spike, name)
                continue
            }
            guard let spike else {
                XCTFail("\(name): Spike erwartet")
                continue
            }
            XCTAssertEqual(spike.ratio, TestSupport.number(expected["ratio"]) ?? .nan, accuracy: 1e-9, name)
            XCTAssertEqual(spike.candleOpenTime,
                           (running + Int64(TestSupport.number(expected["hour"]) ?? 0)) * hour, name)
        }
    }
}
