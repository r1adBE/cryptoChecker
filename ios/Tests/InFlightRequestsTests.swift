import XCTest
@testable import CryptoChecker

/// `InFlightRequests`: Wer kurz nach dem Abbruch des einzigen Wartenden dieselbe Anfrage stellt, erbt nie die
/// abgebrochene, und «abgebrochen» kommt bei einem Wartenden, der weiter will, nie als Fehler an.
final class InFlightRequestsTests: XCTestCase {

    /// Zählt die Aufrufe von `load`; `open` gibt den ersten (absichtlich abbruchtauben) Aufruf frei.
    private actor Probe {
        private(set) var calls = 0
        private(set) var open = false
        func next() -> Int { calls += 1; return calls }
        func release() { open = true }
    }

    func testSpaetankoemmlingStartetNeueAnfrageStattAbgebrocheneZuErben() async throws {
        let requests = InFlightRequests()
        let probe = Probe()
        let load: @Sendable () async throws -> String = {
            let call = await probe.next()
            if call == 1 {
                // Wie eine Übertragung, die den Abbruch erst spät bemerkt
                while !(await probe.open) { await Task.yield() }
                if Task.isCancelled { throw CancellationError() }
            }
            return "Antwort \(call)"
        }
        let first = Task { try await requests.value("k", load: load) }
        while await requests.count == 0 { await Task.yield() }
        first.cancel()
        // Das Aufräumen nach dem Abbruch läuft in einer eigenen Task: warten, bis der Eintrag weg ist
        let until = Date().addingTimeInterval(2)
        while await requests.count > 0, Date() < until { try await Task.sleep(nanoseconds: 1_000_000) }
        let countAfterCancel = await requests.count
        XCTAssertEqual(countAfterCancel, 0)

        let late = try await requests.value("k", load: load)
        XCTAssertEqual(late, "Antwort 2")

        await probe.release()
        do {
            _ = try await first.value
            XCTFail("Der abgebrochene Wartende bekommt keine Antwort")
        } catch {
            XCTAssertTrue(InFlightRequests.isCancellation(error))
        }
    }

    func testGeerbterAbbruchWirdEinmalNeuGesendet() async throws {
        let requests = InFlightRequests()
        let probe = Probe()
        let value = try await requests.value("k") {
            let call = await probe.next()
            if call == 1 { throw URLError(.cancelled) }
            return "Antwort \(call)"
        }
        XCTAssertEqual(value, "Antwort 2")
        let calls = await probe.calls
        XCTAssertEqual(calls, 2)
    }

    func testEchterFehlerWirdNichtWiederholt() async {
        let requests = InFlightRequests()
        let probe = Probe()
        do {
            _ = try await requests.value("k") {
                _ = await probe.next()
                throw URLError(.timedOut)
            }
            XCTFail("Zeitüberschreitung muss ankommen")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .timedOut)
        }
        let calls = await probe.calls
        XCTAssertEqual(calls, 1)
    }
}
