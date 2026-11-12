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

    // MARK: Ablauf mit Journal (`RestoreSteps.execute`)

    /// Protokoll eines Laufs: Schritte, Rückrollen, Abwarten der Merkliste, Löschen des Journals — in dieser Reihenfolge.
    private func run(failAt: Int? = nil, settled results: [Bool]) -> (log: [String], error: Error?) {
        var log: [String] = []
        var answers = results
        var caught: Error?
        do {
            try RestoreSteps.execute({ steps in
                for (i, name) in ["watchlist", "portfolio", "addresses"].enumerated() {
                    try steps.run({
                        if failAt == i { throw Boom() }
                        log.append(name)
                    }, undo: { log.append("undo " + name) })
                }
            }, settled: {
                let answer = answers.isEmpty ? true : answers.removeFirst()
                log.append(answer ? "settled" : "not settled")
                return answer
            }, deleteJournal: { log.append("journal deleted") })
        } catch {
            caught = error
        }
        return (log, caught)
    }

    /// Erfolg: Das Journal fällt erst weg, NACHDEM die Merkliste auf der Platte ist — nie vorher.
    func testJournalDeletedOnlyAfterSnapshotSettled() {
        let result = run(settled: [true])
        XCTAssertNil(result.error)
        XCTAssertEqual(result.log, ["watchlist", "portfolio", "addresses", "settled", "journal deleted"])
    }

    /// Merkliste nicht geschrieben: alle Schritte zurück; ist das Zurückgeschriebene auf der Platte, darf das Journal weg.
    func testUnsettledSnapshotRollsBackEverything() {
        let result = run(settled: [false, true])
        XCTAssertTrue(result.error is RestoreSteps.NotSettled)
        XCTAssertEqual(result.log, ["watchlist", "portfolio", "addresses", "not settled",
                                    "undo addresses", "undo portfolio", "undo watchlist", "settled", "journal deleted"])
    }

    /// Auch das Zurückgeschriebene kommt nicht auf die Platte: Das Journal bleibt (der nächste Start rollt zurück).
    func testJournalKeptWhenRollBackDoesNotSettle() {
        let result = run(settled: [false, false])
        XCTAssertTrue(result.error is RestoreSteps.NotSettled)
        XCTAssertFalse(result.log.contains("journal deleted"))
        XCTAssertEqual(result.log.suffix(2), ["undo watchlist", "not settled"])
    }

    /// Schritt scheitert (Portfolio): Merkliste zurück; Journal nur weg, wenn sie wieder auf der Platte ist.
    func testFailingStepKeepsJournalUntilRollBackSettled() {
        let settledAgain = run(failAt: 1, settled: [true])
        XCTAssertTrue(settledAgain.error is Boom)
        XCTAssertEqual(settledAgain.log, ["watchlist", "undo watchlist", "settled", "journal deleted"])
        let stillOpen = run(failAt: 1, settled: [false])
        XCTAssertTrue(stillOpen.error is Boom)
        XCTAssertEqual(stillOpen.log, ["watchlist", "undo watchlist", "not settled"])
    }

    /// `BackupManager.writeRestored` geht über diesen Ablauf und wartet die Schreib-Warteschlange ab; nirgends sonst
    /// wird das Journal nach einem Lauf gelöscht.
    func testWriteRestoredUsesExecuteAndFlush() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/BackupManager+Restore.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        XCTAssertTrue(source.contains("try RestoreSteps.execute({ steps in"))
        XCTAssertTrue(source.contains("settled: { restoreWritesSettled() }, deleteJournal: { if journal { RestoreJournal.delete() } })"))
        // Abwarten immer; nur der UI-Test (kein Schlüsselbund im unsignierten Simulator) übergeht das Ergebnis
        XCTAssertTrue(source.contains("let written = SharedStorage.flushSnapshotWrites()\n        #if DEBUG\n        if UITesting.isActive { return true }\n        #endif\n        return written"))
        XCTAssertEqual(source.components(separatedBy: "RestoreJournal.delete()").count, 2)
        XCTAssertTrue(source.contains("throw Failure.invalid(watchlistStep)"))
    }
}
