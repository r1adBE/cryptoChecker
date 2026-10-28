import XCTest
@testable import CryptoChecker

/// Eigene Kerzen je Börse (`Parity/own_candles.json`): Adressen der Anfragen und gelesene Kerzen aus
/// Antworten nach den öffentlichen Dokumentationen — wie `OwnCandlesParityTest.kt`.
final class OwnCandlesParityTests: XCTestCase {

    /// Aufgezeichnete Anfragen (die Antwort-Funktion ist `@Sendable`).
    private final class Recorder: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
        private let lock = NSLock()
        private var urls: [String] = []
        func add(_ url: String) { lock.lock(); urls.append(url); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return urls }
    }

    private func pair(_ raw: Any?) -> CandlePair {
        let p = raw as? [String: Any] ?? [:]
        return CandlePair(exchangeKey: p["exchangeKey"] as? String ?? "",
                          exchangeName: p["exchangeName"] as? String ?? "",
                          base: p["base"] as? String ?? "",
                          quote: p["quote"] as? String ?? "",
                          pairId: p["pairId"] as? String,
                          contractType: FuturesContractType(backupName: p["contract"] as? String ?? "NONE"),
                          tradFi: p["tradFi"] as? Bool ?? false)
    }

    private func interval(_ name: String) -> CandleInterval {
        switch name {
        case "H1": return .h1
        case "H4": return .h4
        case "D1": return .d1
        default: return .w1
        }
    }

    private func near(_ label: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else { return XCTFail("\(label): expected value missing") }
        guard let actual else { return XCTFail("\(label): expected \(expected), got nil") }
        XCTAssertLessThanOrEqual(abs(expected - actual), max(abs(expected) * 1e-9, 1e-9), label)
    }

    func testFetch() async throws {
        let cases = TestSupport.list(try TestSupport.fixture("own_candles")["fetch"])
        XCTAssertGreaterThanOrEqual(cases.count, 30)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let pair = self.pair(c["pair"])
            let responses = (c["responses"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }
            let recorder = Recorder()
            // POST: Schlüssel «POST <Adresse> <Inhalt>»
            let http = CandleHTTP(get: { url in
                recorder.add(url)
                return responses[url]
            }, post: { url, json in
                let key = "POST \(url) \(json)"
                recorder.add(key)
                return responses[key]
            }, now: Int64(TestSupport.number(c["now"]) ?? 0))
            guard let source = ExchangeCandleRegistry.source(for: pair) else {
                XCTFail("\(name): source")
                continue
            }
            let limit = Int(TestSupport.number(c["limit"]) ?? 0)
            let result = await source.fetch(pair, interval: interval(c["interval"] as? String ?? ""), limit: limit, http: http)
            XCTAssertEqual(recorder.all, c["urls"] as? [String] ?? [], "\(name): urls")
            guard let rows = c["expected"] as? [Any] else {
                XCTAssertNil(result, "\(name): expected no candles")
                continue
            }
            guard let result else {
                XCTFail("\(name): expected candles")
                continue
            }
            XCTAssertEqual(result.count, rows.count, "\(name): count")
            for (i, raw) in rows.enumerated() where i < result.count {
                let r = raw as? [Any] ?? []
                let k = result[i]
                XCTAssertEqual(k.openTime, Int64(TestSupport.number(r[0]) ?? -1), "\(name)[\(i)] openTime")
                near("\(name)[\(i)] open", TestSupport.number(r[1]), k.open)
                near("\(name)[\(i)] high", TestSupport.number(r[2]), k.high)
                near("\(name)[\(i)] low", TestSupport.number(r[3]), k.low)
                near("\(name)[\(i)] close", TestSupport.number(r[4]), k.close)
                near("\(name)[\(i)] volume", TestSupport.number(r[5]), k.volume)
                if let qv = TestSupport.number(r[6]) {
                    near("\(name)[\(i)] quoteVolume", qv, k.quoteVolume)
                } else {
                    XCTAssertNil(k.quoteVolume, "\(name)[\(i)] quoteVolume")
                }
            }
        }
    }

    func testSupports() throws {
        let cases = TestSupport.list(try TestSupport.fixture("own_candles")["supports"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let pair = self.pair(c["pair"])
            let source = ExchangeCandleRegistry.source(for: pair)
            if let expected = c["source"] as? String {
                XCTAssertTrue(source?.exchangeKeys.contains(expected) ?? false, "\(name): source for \(expected)")
            } else {
                XCTAssertNil(source, "\(name): no own source")
            }
            XCTAssertEqual(OwnCandlePolicy.allowsFallback(pair), c["fallback"] as? Bool ?? true, "\(name): fallback")
            // Eigene Kerzen nur in 1 h, 4 h und 1 Tag
            if source != nil {
                XCTAssertTrue(ExchangeCandleRegistry.supports(pair, interval: .d1))
                XCTAssertFalse(ExchangeCandleRegistry.supports(pair, interval: .w1))
            }
        }
    }
}
