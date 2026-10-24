import XCTest
@testable import CryptoChecker

/// Speichern des CSV-Imports (`AppData.insertImportedPortfolioTxs` → `PortfolioImportSave`) mit einem Fake-Speicher:
/// ein Schreibvorgang, Abbruch nach dem Zusammenstellen (nichts geschrieben), «inzwischen anders gespeichert»,
/// überholtes Schreiben und Fehler; dazu `PortfolioWriteOrder`, `CsvImportRun.cancel()` und Fortschritt aus einer
/// losgelösten Aufgabe. Wie `CsvImportSaveTest.kt`.
@MainActor
final class PortfolioImportSaveTests: XCTestCase {

    /// Portfolio-Datei im Speicher mit Nummer der Schreib-Reihenfolge wie `AppData.portfolioWrites`.
    @MainActor
    private final class FakeDisk {
        var file: PortfolioFile
        var generation = 0
        var writes: [PortfolioFile] = []
        var reads = 0
        /// Vor dem n-ten Lesen (ab 1) ausgeführt.
        var beforeRead: [Int: @MainActor () -> Void] = [:]
        /// Vor dem n-ten Schreibversuch (ab 1) ausgeführt (z. B. ein anderer schreibt dazwischen).
        var beforeWrite: [Int: @MainActor () -> Void] = [:]
        var attempts = 0
        /// Fester Ausgang statt Schreiben (z. B. `.failed`).
        var forced: PortfolioBackgroundWrite?

        init(_ file: PortfolioFile) { self.file = file }

        func saver() -> PortfolioImportSave {
            let current: @MainActor () -> (file: PortfolioFile, generation: Int) = { [unowned self] in
                self.reads += 1
                self.beforeRead[self.reads]?()
                return (self.file, self.generation)
            }
            let write: @MainActor (PortfolioFile, Int) async -> PortfolioBackgroundWrite = { [unowned self] next, gen in
                self.attempts += 1
                self.beforeWrite[self.attempts]?()
                if let forced = self.forced { return forced }
                guard gen == self.generation else { return .superseded }
                self.generation += 1
                self.file = next
                self.writes.append(next)
                return .saved
            }
            return PortfolioImportSave(current: current, write: write)
        }

        /// Ein anderer Schreibweg (z. B. Transaktion von Hand) speichert `tx` dazu.
        func otherWrite(_ tx: PortfolioTx) {
            file.transactions.append(tx)
            file.nextId = max(file.nextId, tx.id + 1)
            generation += 1
        }
    }

    private let manual = PortfolioTx(id: 7, coin: "ETH", type: .BUY, amount: 1, priceUsdt: 2_000, time: 1)
    private let noProgress: @Sendable (CsvImportProgress) -> Void = { _ in }

    private func imported(_ count: Int) -> [PortfolioTx] {
        (0..<count).map { i in
            var tx = PortfolioTx(id: 0, coin: "BTC", type: .BUY, amount: 0.1, priceUsdt: 60_000, time: Int64(1_000 + i))
            tx.importKey = "op-\(i)"
            return tx
        }
    }

    private func importKeys(_ file: PortfolioFile) -> [String] { file.transactions.compactMap(\.importKey) }

    func testImportWrittenOnceWithProgressToTotal() async throws {
        let disk = FakeDisk(PortfolioFile(transactions: [manual], nextId: 8))
        let run = CsvImportRun()
        let report = run.begin(.saving)
        let result = await disk.saver().run(imported(3), batch: "b1", progress: report)
        XCTAssertEqual(result, .written(3))
        XCTAssertEqual(disk.writes.count, 1, "ein Schreibvorgang")
        XCTAssertEqual(importKeys(disk.file), ["op-0", "op-1", "op-2"])
        XCTAssertEqual(disk.file.transactions.map(\.id), [7, 8, 9, 10])
        XCTAssertTrue(disk.file.transactions.dropFirst().allSatisfy { $0.importBatch == "b1" })
        // Fortschritt aus der losgelösten Arbeit kommt auf dem MainActor an
        try await waitUntil { run.progress?.done == 3 }
        XCTAssertEqual(run.progress, CsvImportProgress(step: .SAVE, done: 3, total: 3))
    }

    /// «Abbrechen» nach dem Zusammenstellen, vor dem Schreiben: `.cancelled`, nichts geschrieben.
    func testCancelAfterBuildWritesNothing() async {
        let base = PortfolioFile(transactions: [manual], nextId: 8)
        let disk = FakeDisk(base)
        // Zweites Lesen = nach dem Zusammenstellen: die Aufgabe wird abgebrochen
        let cancelTask: @MainActor () -> Void = { withUnsafeCurrentTask { $0?.cancel() } }
        disk.beforeRead[2] = cancelTask
        let saver = disk.saver()
        let list = imported(5)
        let progress = noProgress
        let result = await Task { await saver.run(list, batch: "b1", progress: progress) }.value
        XCTAssertEqual(result, .cancelled)
        XCTAssertEqual(disk.reads, 2)
        XCTAssertEqual(disk.attempts, 0, "kein Schreibversuch")
        XCTAssertTrue(disk.writes.isEmpty)
        XCTAssertEqual(disk.file.transactions, base.transactions, "Daten unverändert")
        XCTAssertEqual(disk.file.nextId, base.nextId)
    }

    /// Inzwischen anders gespeichert: auf dem aktuellen Stand noch einmal zusammengestellt (im Hintergrund), dann ein
    /// Schreibvorgang mit beidem; Ids ohne Doppel.
    func testChangedMeanwhileRebuildsOnCurrentState() async {
        let disk = FakeDisk(PortfolioFile(transactions: [manual], nextId: 8))
        let other = PortfolioTx(id: 8, coin: "SOL", type: .BUY, amount: 2, priceUsdt: 150, time: 2)
        let otherWrite: @MainActor () -> Void = { disk.otherWrite(other) }
        disk.beforeRead[2] = otherWrite
        let result = await disk.saver().run(imported(3), batch: "b1", progress: noProgress)
        XCTAssertEqual(result, .written(3))
        XCTAssertEqual(disk.writes.count, 1)
        XCTAssertEqual(disk.reads, 3, "neu zusammengestellt, dann erneut geprüft")
        XCTAssertEqual(disk.file.transactions.map(\.id), [7, 8, 9, 10, 11])
        XCTAssertEqual(disk.file.transactions[1], other, "Änderung von dazwischen bleibt")
        XCTAssertEqual(importKeys(disk.file), ["op-0", "op-1", "op-2"])
    }

    /// Ein anderer schreibt, während das Schreiben im Hintergrund wartet: überholt → auf dessen Stand noch einmal.
    func testSupersededWriteRetriesOnNewerState() async {
        let disk = FakeDisk(PortfolioFile(transactions: [manual], nextId: 8))
        let other = PortfolioTx(id: 8, coin: "SOL", type: .BUY, amount: 2, priceUsdt: 150, time: 2)
        let otherWrite: @MainActor () -> Void = { disk.otherWrite(other) }
        disk.beforeWrite[1] = otherWrite
        let result = await disk.saver().run(imported(2), batch: nil, progress: noProgress)
        XCTAssertEqual(result, .written(2))
        XCTAssertEqual(disk.attempts, 2)
        XCTAssertEqual(disk.writes.count, 1)
        XCTAssertEqual(disk.file.transactions.map(\.coin), ["ETH", "SOL", "BTC", "BTC"])
    }

    func testFailedWriteReportsFailed() async {
        let base = PortfolioFile(transactions: [manual], nextId: 8)
        let disk = FakeDisk(base)
        disk.forced = .failed
        let result = await disk.saver().run(imported(2), batch: nil, progress: noProgress)
        XCTAssertEqual(result, .failed)
        XCTAssertEqual(disk.file.transactions, base.transactions)
    }

    /// Ständig überholt (andere schreiben immer dazwischen): nach `maxRounds` Runden `.failed`, nichts geschrieben.
    func testEndlesslySupersededGivesUp() async {
        let disk = FakeDisk(PortfolioFile(transactions: [manual], nextId: 8))
        disk.forced = .superseded
        let result = await disk.saver().run(imported(2), batch: nil, progress: noProgress)
        XCTAssertEqual(result, .failed)
        XCTAssertEqual(disk.attempts, PortfolioImportSave.maxRounds)
        XCTAssertTrue(disk.writes.isEmpty)
    }

    func testAllRowsKnownWritesNothing() async {
        var known = imported(1)[0]
        known.id = 8
        let disk = FakeDisk(PortfolioFile(transactions: [manual, known], nextId: 9))
        let result = await disk.saver().run(imported(1), batch: nil, progress: noProgress)
        XCTAssertEqual(result, .written(0))
        XCTAssertEqual(disk.attempts, 0)
        XCTAssertTrue(result.isWritten)
        XCTAssertFalse(PortfolioImportWrite.cancelled.isWritten)
    }

    // MARK: Schreib-Reihenfolge

    func testWriteOrderSkipsOutdatedWrite() {
        let order = PortfolioWriteOrder()
        let seen = order.current
        XCTAssertEqual(order.write { "von Hand" }, "von Hand")
        XCTAssertNil(order.write(ifUnchangedSince: seen) { "Import" }, "überholt: nicht geschrieben")
        let fresh = order.current
        let done = order.write(ifUnchangedSince: fresh) { "Import" }
        XCTAssertEqual(done?.result, "Import")
        XCTAssertEqual(done?.generation, fresh + 1)
        XCTAssertEqual(order.current, fresh + 1)
    }

    // MARK: Anzeige

    /// «Abbrechen»: Aufgabe abgebrochen, Anzeige weg; eine späte Meldung zählt nicht.
    func testRunCancelStopsTaskAndHidesProgress() async throws {
        let run = CsvImportRun()
        let report = run.begin(.saving)
        let started = expectation(description: "gestartet")
        let task = Task<Void, Never> {
            started.fulfill()
            while !Task.isCancelled { try? await Task.sleep(nanoseconds: 5_000_000) }
        }
        run.attach(task)
        await fulfillment(of: [started], timeout: 5)
        run.cancel()
        await task.value
        XCTAssertTrue(task.isCancelled)
        XCTAssertNil(run.phase)
        XCTAssertNil(run.progress)
        report(CsvImportProgress(step: .SAVE, done: 10, total: 10))
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertNil(run.progress, "späte Meldung nach dem Abbrechen zählt nicht")
    }

    /// Meldung aus einer losgelösten Aufgabe (anderer Thread): kommt auf dem MainActor an, ohne Absturz.
    func testProgressFromDetachedTaskArrives() async throws {
        let run = CsvImportRun()
        let report = run.begin(.loading)
        let progress = CsvImportProgress(step: .PREVIEW, done: 300, total: 1_000)
        await Task.detached { report(progress) }.value
        try await waitUntil { run.progress == progress }
        XCTAssertEqual(run.phase, .loading)
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<200 where !condition() {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTAssertTrue(condition())
    }
}
