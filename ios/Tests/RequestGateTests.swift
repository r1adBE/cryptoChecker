import XCTest
@testable import CryptoChecker

/// `RequestGate`: höchstens n Anfragen je Host (und insgesamt) gleichzeitig, weitere warten vor der URLSession — dort
/// liefen ihre Fristen sonst schon (Schein-Zeitüberschreitung, Pause der Börse). Wie OkHttp-Dispatcher und CallGuard
/// auf Android (CallGuardTest.kt).
final class RequestGateTests: XCTestCase {
    /// Zählt gleichzeitig laufende Arbeiten je Host.
    private actor Meter {
        private var now: [String: Int] = [:]
        private(set) var peak: [String: Int] = [:]
        private(set) var done = 0

        func enter(_ host: String) {
            now[host, default: 0] += 1
            peak[host] = max(peak[host, default: 0], now[host, default: 0])
        }

        func leave(_ host: String) {
            now[host, default: 0] -= 1
            done += 1
        }
    }

    private static func work(_ gate: RequestGate, _ meter: Meter, host: String, millis: UInt64) async throws -> String {
        try await gate.run(host: host) {
            await meter.enter(host)
            try await Task.sleep(nanoseconds: millis * 1_000_000)
            await meter.leave(host)
            return host
        }
    }

    func testAtMostMaxPerHostRunAtOnceAndAllFinish() async throws {
        let gate = RequestGate(maxTotal: 32, maxPerHost: 2)
        let meter = Meter()
        let results = try await withThrowingTaskGroup(of: String.self) { group -> [String] in
            for _ in 0..<6 { group.addTask { try await Self.work(gate, meter, host: "a.test", millis: 50) } }
            var all: [String] = []
            for try await value in group { all.append(value) }
            return all
        }
        XCTAssertEqual(results.count, 6)
        let peak = await meter.peak["a.test"]
        XCTAssertEqual(peak, 2)
        let running = await gate.runningCount
        XCTAssertEqual(running, 0)
    }

    func testFullHostDoesNotBlockAnotherHost() async throws {
        let gate = RequestGate(maxTotal: 32, maxPerHost: 1)
        let meter = Meter()
        let slow = Task { try await Self.work(gate, meter, host: "slow.test", millis: 1_000) }
        let queued = Task { try await Self.work(gate, meter, host: "slow.test", millis: 10) }
        let started = Date()
        let other = try await Self.work(gate, meter, host: "fast.test", millis: 10)
        XCTAssertEqual(other, "fast.test")
        XCTAssertLessThan(Date().timeIntervalSince(started), 0.8)
        slow.cancel()
        queued.cancel()
        _ = try? await slow.value
        _ = try? await queued.value
    }

    func testCancelledWaiterLeavesTheQueue() async throws {
        let gate = RequestGate(maxTotal: 32, maxPerHost: 1)
        let meter = Meter()
        let blocker = Task { try await Self.work(gate, meter, host: "h.test", millis: 300) }
        try await Task.sleep(nanoseconds: 50_000_000)
        let waiter = Task { try await Self.work(gate, meter, host: "h.test", millis: 10) }
        try await Task.sleep(nanoseconds: 50_000_000)
        waiter.cancel()
        do {
            _ = try await waiter.value
            XCTFail("abgebrochen erwartet")
        } catch {
            XCTAssertTrue(error is CancellationError, "war \(error)")
        }
        _ = try await blocker.value
        // Platz wieder frei: die nächste Anfrage läuft sofort
        let next = try await Self.work(gate, meter, host: "h.test", millis: 10)
        XCTAssertEqual(next, "h.test")
        let running = await gate.runningCount
        XCTAssertEqual(running, 0)
    }
}
