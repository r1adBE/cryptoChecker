import XCTest
@testable import CryptoChecker

/// Ablauf eines Exports (`ExportRun`): Abbrechen während des Laufs → `idle`, das Ergebnis wird verworfen (keine Datei
/// angeboten); Fehler mit Art und «Erneut versuchen». Gegenstück: `ExportRunnerTest.kt`.
@MainActor
final class ExportRunTests: XCTestCase {
    /// Zählt die Versuche eines Laufs (auf dem MainActor, wie die Läufe selbst).
    @MainActor
    private final class Attempts {
        var count = 0
    }

    /// Rechnet «lange», ohne auf Abbruch zu achten (wie die Exporte selbst): das Ergebnis kommt trotzdem an.
    private static func slowBuild() async -> String {
        for _ in 0..<20 { await Task.yield() }
        return "a;b"
    }

    func testCancelExportGehtAufIdleUndVerwirftDatei() async throws {
        let run = ExportRun()
        let build: ExportRun.Build = { await ExportRunTests.slowBuild() }
        let task = try XCTUnwrap(run.start(fileName: "x.csv", build: build))
        XCTAssertEqual(run.state, .running)

        run.cancel()
        XCTAssertEqual(run.state, .idle)
        await task.value
        XCTAssertNil(run.document)
        XCTAssertFalse(run.exporting)
        XCTAssertEqual(run.state, .idle)
    }

    func testFertigerExportWirdAngeboten() async throws {
        let run = ExportRun()
        let build: ExportRun.Build = { "x" }
        let task = try XCTUnwrap(run.start(fileName: "ok.csv", build: build))
        await task.value
        XCTAssertEqual(run.state, .idle)
        XCTAssertTrue(run.exporting)
        XCTAssertEqual(run.fileName, "ok.csv")
        XCTAssertEqual(run.document?.data, Data("x".utf8))
        run.cancel() // nichts mehr am Laufen: ohne Wirkung
        XCTAssertTrue(run.exporting)
    }

    func testKurseFehlenUndErneutVersuchen() async throws {
        let run = ExportRun()
        let attempts = Attempts()
        let build: ExportRun.Build = {
            attempts.count += 1
            if attempts.count == 1 { throw URLError(.notConnectedToInternet) }
            return "ok"
        }
        let first = try XCTUnwrap(run.start(fileName: "c.csv", build: build))
        await first.value
        XCTAssertEqual(run.state, .failed(.PRICES))
        XCTAssertNil(run.document)

        run.retry()
        XCTAssertEqual(run.state, .running)
        // Auf den neuen Lauf warten (er endet auf dem MainActor)
        for _ in 0..<50 where run.running { await Task.yield() }
        XCTAssertEqual(run.state, .idle)
        XCTAssertEqual(run.document?.data, Data("ok".utf8))
        XCTAssertEqual(attempts.count, 2)
    }

    func testNurEinLaufZurZeit() throws {
        let run = ExportRun()
        let build: ExportRun.Build = { await ExportRunTests.slowBuild() }
        XCTAssertNotNil(run.start(fileName: "a.csv", build: build))
        XCTAssertNil(run.start(fileName: "b.csv", build: build))
        run.cancel()
    }
}
