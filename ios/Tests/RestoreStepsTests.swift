import XCTest
@testable import CryptoChecker

/// Wiederherstellen mit Rückrollen (`RestoreSteps` in `BackupManager.restore`):
/// scheitert das Portfolio, kommen Merkliste, Alarme und Einstellungen zurück (wie Android).
final class RestoreStepsTests: XCTestCase {
    private struct Boom: Error {}

    /// Merkliste ersetzt, Portfolio scheitert → Merkliste zurück, Fehler weitergereicht.
    func testFailingPortfolioRollsBackWatchlist() {
        var watchlist = ["old"]
        var portfolio = ["oldTx"]
        var steps = RestoreSteps()
        var caught: Error?
        do {
            let before = watchlist
            steps.run({ watchlist = ["new"] }, undo: { watchlist = before })
            XCTAssertEqual(watchlist, ["new"])
            try steps.run({ throw Boom() }, undo: { portfolio = [] })
        } catch {
            steps.rollBack()
            caught = error
        }
        XCTAssertTrue(caught is Boom)
        XCTAssertEqual(watchlist, ["old"])
        // Der gescheiterte Schritt hat nichts geschrieben und wird nicht «zurückgesetzt»
        XCTAssertEqual(portfolio, ["oldTx"])
        XCTAssertEqual(steps.completed, 0)
    }

    /// Rückwärts zurücksetzen: der zuletzt gelungene Schritt zuerst.
    func testRollBackRunsInReverseOrder() throws {
        var log: [String] = []
        var steps = RestoreSteps()
        steps.run({ log.append("a") }, undo: { log.append("undo a") })
        steps.run({ log.append("b") }, undo: { log.append("undo b") })
        XCTAssertEqual(steps.completed, 2)
        XCTAssertThrowsError(try steps.run({ throw Boom() }, undo: { log.append("undo c") }))
        steps.rollBack()
        XCTAssertEqual(log, ["a", "b", "undo b", "undo a"])
        // Zweites Rückrollen tut nichts mehr
        steps.rollBack()
        XCTAssertEqual(log.count, 4)
    }

    /// Alles gelingt: nichts wird zurückgesetzt.
    func testSuccessKeepsNewState() {
        var value = 1
        var steps = RestoreSteps()
        steps.run({ value = 2 }, undo: { value = 1 })
        steps.run({ value += 1 }, undo: { value -= 1 })
        XCTAssertEqual(value, 3)
        XCTAssertEqual(steps.completed, 2)
    }
}
