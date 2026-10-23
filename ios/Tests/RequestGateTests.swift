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
        // Langsame Anfrage 5 s (wird am Ende abgebrochen): viel Luft zur Schranke unten, auch auf einer langsamen Maschine
        let slow = Task { try await Self.work(gate, meter, host: "slow.test", millis: 5_000) }
        let queued = Task { try await Self.work(gate, meter, host: "slow.test", millis: 10) }
        let started = Date()
        let other = try await Self.work(gate, meter, host: "fast.test", millis: 10)
        XCTAssertEqual(other, "fast.test")
        XCTAssertLessThan(Date().timeIntervalSince(started), 4.0)
        slow.cancel()
        queued.cancel()
        _ = try? await slow.value
        _ = try? await queued.value
    }

    /// Einmal geöffnet, bleibt offen: hält eine Arbeit fest, bis der Test sie freigibt (statt fester Wartezeiten).
    private actor Latch {
        private(set) var isOpen = false
        func open() { isOpen = true }
    }

    func testCancelledWaiterLeavesTheQueue() async throws {
        let gate = RequestGate(maxTotal: 32, maxPerHost: 1)
        let meter = Meter()
        let latch = Latch()
        // Blockiert den einzigen Platz, bis der Test ihn freigibt — unabhängig vom Takt der CI-Maschine
        let blocker = Task {
            try await gate.run(host: "h.test") { () async throws -> String in
                await meter.enter("h.test")
                for _ in 0..<3_000 {
                    if await latch.isOpen { break }
                    try await Task.sleep(nanoseconds: 10_000_000)
                }
                await meter.leave("h.test")
                return "h.test"
            }
        }
        // Erst weiter, wenn der Blocker den Platz hat (sonst käme der Wartende womöglich zuerst dran)
        for _ in 0..<500 {
            if await meter.peak["h.test"] != nil { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let entered = await meter.peak["h.test"]
        XCTAssertEqual(entered, 1)
        let waiter = Task { try await Self.work(gate, meter, host: "h.test", millis: 10) }
        // Warten, bis er wirklich in der Schlange steht (statt fester 50 ms) — so prüft der Test sicher
        // das Verlassen der Schlange und nicht nur die Prüfung vor dem Einreihen
        for _ in 0..<500 {
            if await gate.waitingCount == 1 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let waitingBefore = await gate.waitingCount
        XCTAssertEqual(waitingBefore, 1)
        waiter.cancel()
        do {
            _ = try await waiter.value
            XCTFail("abgebrochen erwartet")
        } catch {
            XCTAssertTrue(error is CancellationError, "war \(error)")
        }
        let waitingAfter = await gate.waitingCount
        XCTAssertEqual(waitingAfter, 0)
        await latch.open()
        _ = try await blocker.value
        // Platz wieder frei: die nächste Anfrage läuft sofort
        let next = try await Self.work(gate, meter, host: "h.test", millis: 10)
        XCTAssertEqual(next, "h.test")
        let running = await gate.runningCount
        XCTAssertEqual(running, 0)
    }
}
