import XCTest
@testable import CryptoChecker

/// Reihenfolge der Schreibschritte beim Wiederherstellen samt Journal und Rückrollen (`Parity/restore_steps.json`):
/// `RestoreSteps.execute` mit Fälschungen. Gegenstück: Android `RestoreStepsParityTest.kt`.
final class RestoreStepsParityTests: XCTestCase {
    private typealias S = TestSupport
    private struct Boom: Error {}

    func testAblaufWieGemeinsameFaelle() throws {
        let fixture = try S.fixture("restore_steps")
        let platform = try XCTUnwrap(fixture["ios"] as? [String: Any])
        let steps = try XCTUnwrap(platform["steps"] as? [String])
        let rollbackOrder = try XCTUnwrap(platform["rollbackOrder"] as? [String])
        let rollsBackFailedStep = try XCTUnwrap(platform["rollsBackFailedStep"] as? Bool)
        XCTAssertEqual(rollbackOrder, Array(steps.reversed()))
        let cases = S.list(fixture["cases"]).filter { ($0["platform"] as? String ?? "ios") == "ios" }
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let failAt = S.number(c["failAt"]).map { Int($0) }
            let notSettled = c["notSettled"] as? Bool ?? false
            let rollbackComplete = try XCTUnwrap(c["rollbackComplete"] as? Bool, name)
            var log = ["journal written"]
            var state = Dictionary(uniqueKeysWithValues: steps.map { ($0, "old") })
            var settledCalls = 0
            var caught: Error?
            do {
                try RestoreSteps.execute({ chain in
                    for (i, step) in steps.enumerated() {
                        try chain.run({
                            log.append("write " + step)
                            if failAt == i { throw Boom() }
                            state[step] = "new"
                        }, undo: {
                            log.append("undo " + step)
                            state[step] = "old"
                        })
                    }
                }, settled: {
                    settledCalls += 1
                    // Erster Aufruf nach gelungenen Schritten: Merkliste auf der Platte? Nach dem Rückrollen: der
                    // zurückgeschriebene Stand?
                    if failAt == nil && settledCalls == 1 { return !notSettled }
                    return rollbackComplete
                }, deleteJournal: { log.append("journal deleted") })
            } catch {
                caught = error
            }

            // Erwartetes Protokoll aus den Regeln der Datei
            let failed = failAt != nil || notSettled
            let started = failAt.map { Array(steps.prefix($0 + 1)) } ?? steps
            let rolledBack: [String]
            if let failAt {
                rolledBack = Array(steps.prefix(rollsBackFailedStep ? failAt + 1 : failAt))
            } else {
                rolledBack = notSettled ? steps : []
            }
            let expected = ["journal written"] + started.map { "write " + $0 }
                + rollbackOrder.filter { rolledBack.contains($0) }.map { "undo " + $0 }
                + ((c["journalDeleted"] as? Bool ?? false) ? ["journal deleted"] : [])
            XCTAssertEqual(log, expected, name)
            XCTAssertEqual(caught != nil, failed, name)
            if notSettled { XCTAssertTrue(caught is RestoreSteps.NotSettled, name) }
            let wanted = try XCTUnwrap(c["state"] as? String, name)
            XCTAssertEqual(state, Dictionary(uniqueKeysWithValues: steps.map { ($0, wanted) }), name)
        }
    }

    /// Reihenfolge der Schritte in `BackupManager.runRestoreSteps` = die der Datei.
    func testSchritteWieImCode() throws {
        let fixture = try S.fixture("restore_steps")
        let steps = try XCTUnwrap((fixture["ios"] as? [String: Any])?["steps"] as? [String])
        XCTAssertEqual(steps, ["watchlist", "portfolio", "addresses"])
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Services/BackupManager+Restore.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        let marks = ["data.replaceAll(snapshot: snapshot, settings: input.newSettings, favorites: input.favorites)",
                     "guard data.restorePortfolio(input.portfolio) else",
                     "guard data.replaceWatchedAddresses(input.watched) != nil else"]
        let positions = try marks.map { try XCTUnwrap(source.range(of: $0), $0).lowerBound }
        XCTAssertEqual(positions, positions.sorted())
    }
}
