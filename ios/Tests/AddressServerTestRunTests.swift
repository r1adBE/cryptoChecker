import XCTest
@testable import CryptoChecker

/// «Testen» des eigenen Servers (`AddressServerTestRun`): Test A abbrechen (Eingabe geändert), erneut A testen — das
/// späte Ergebnis des ersten Abrufs (der den Abbruch nicht bemerkt) überschreibt `.running(A)` des zweiten nicht.
/// Gegenstück: `AddressServerTestRunTest.kt`.
@MainActor
final class AddressServerTestRunTests: XCTestCase {
    private let url = "https://node.example.org"

    /// Abruf-Attrappe, die den Abbruch nicht bemerkt: liefert erst nach `release(n)` (n-ter Aufruf: n × 111).
    @MainActor
    private final class FakeServer {
        var calls = 0
        var released: Set<Int> = []

        func load() async -> Int64? {
            calls += 1
            let n = calls
            while !released.contains(n) { await Task.yield() }
            return Int64(n * 111)
        }
    }

    private func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<1_000 where !condition() { await Task.yield() }
    }

    func testSpaetesErgebnisUeberschreibtNeuerenTestNicht() async {
        let server = FakeServer()
        let run = AddressServerTestRun(load: { _ in await server.load() })
        let first = run.start(url)
        await waitFor { server.calls == 1 }
        run.reset()
        XCTAssertEqual(run.state, .idle)
        let second = run.start(url)
        await waitFor { server.calls == 2 }
        XCTAssertEqual(run.state, .running(url))

        server.released.insert(1)
        await first?.value
        XCTAssertEqual(run.state, .running(url))

        server.released.insert(2)
        await second?.value
        XCTAssertEqual(run.state, .ok(url, 222))
    }

    func testZweiterStartWaehrendEinesTestsZaehltNicht() async {
        let server = FakeServer()
        let run = AddressServerTestRun(load: { _ in await server.load() })
        let first = run.start(url)
        XCTAssertNil(run.start("https://other.example"))
        server.released.insert(1)
        await first?.value
        XCTAssertEqual(server.calls, 1)
        XCTAssertEqual(run.state, .ok(url, 111))
    }
}
