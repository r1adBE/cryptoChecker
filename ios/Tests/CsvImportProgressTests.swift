import XCTest
@testable import CryptoChecker

/// Fortschritt und Abbruch des CSV-Imports im Hintergrund (`CsvProgressThrottle`, `CsvImportInsert`,
/// `CsvImportMapping.preview` mit `onRow`, `AppData.withImported`, `CsvImportBackground`, `CsvImportRun`) — wie
/// `CsvImportProgressTest.kt` und `CsvImportSaveTest.kt`.
@MainActor
final class CsvImportProgressTests: XCTestCase {

    /// Relai-Export mit `rows` Käufen (je eigene Kennung, CHF-Preis).
    private func relai(_ rows: Int) -> String {
        var text = "Date,Transaction Type,BTC Amount,BTC Price,Currency Pair,Fiat Amount (excl. fees),Fiat Currency,"
            + "Fee,Fee Currency,Destination,Operation ID,Counterparty\r\n"
        for i in 0..<rows {
            let time = String(format: "2024-03-%02dT%02d:%02d:00Z", 1 + i / 1440, (i / 60) % 24, i % 60)
            text += time + String(format: ",Buy,0.001,60000.00,BTC/CHF,60.00,CHF,0.50,CHF,bc1q,op-%05d,Relai\r\n", i)
        }
        return text
    }

    private let fx: (String, Int64) -> Decimal? = { c, _ in c == "CHF" ? Decimal(string: "0.8") : nil }

    // MARK: Drosselung

    func testThrottleReportsAtMostEveryHundredRowsAndEndsAtTotal() {
        var now: Int64 = 0
        var seen: [CsvImportProgress] = []
        var throttle = CsvProgressThrottle(step: .SAVE, total: 1_000, clock: { now }) { seen.append($0) }
        throttle.start()
        for row in 1...1_000 {
            now += 1 // 1 ms je Zeile
            throttle.row(row)
        }
        XCTAssertEqual(seen.first, CsvImportProgress(step: .SAVE, done: 0, total: 1_000))
        XCTAssertEqual(seen.last, CsvImportProgress(step: .SAVE, done: 1_000, total: 1_000))
        let steps = zip(seen, seen.dropFirst()).map { $1.done - $0.done }
        XCTAssertTrue(steps.dropLast().allSatisfy { $0 >= CsvProgressThrottle.minRows }, "\(steps)")
        XCTAssertEqual(seen.filter { $0.done == 1_000 }.count, 1, "Ende genau einmal")
    }

    func testThrottleWaitsForFiftyMillisWhenRowsAreFast() {
        var now: Int64 = 0
        var seen: [Int] = []
        var throttle = CsvProgressThrottle(step: .PREVIEW, total: 10_000, clock: { now }) { seen.append($0.done) }
        for row in 1...10_000 {
            if row % 1_000 == 0 { now += 50 } // 1000 Zeilen je 50 ms
            throttle.row(row)
        }
        XCTAssertEqual(seen, (1...10).map { $0 * 1_000 })
    }

    func testThrottleIgnoresRepeatsAndReportsLastRowEvenWhenQuick() {
        var seen: [Int] = []
        var throttle = CsvProgressThrottle(step: .SAVE, total: 3, clock: { 0 }) { seen.append($0.done) }
        throttle.row(1)
        throttle.row(3)
        throttle.row(3)
        XCTAssertEqual(seen, [3])
        XCTAssertEqual(CsvImportProgress(step: .SAVE, done: 1, total: 2).fraction, 0.5)
        XCTAssertEqual(CsvImportProgress(step: .SAVE, done: 0, total: 0).fraction, 0)
    }

    // MARK: Anlegen

    func testInsertSkipsKnownKeysReportsProgressToTotal() throws {
        var known: Set<String> = ["old"]
        var rows: [Int] = []
        var staged: [String] = []
        let count = try CsvImportInsert.run(["a", "b", "a", "c", "old"], known: &known, keys: { [$0] }, cancelled: { false },
                                            onRow: { rows.append($0) }) { staged.append($0); return true }
        XCTAssertEqual(count, 3)
        XCTAssertEqual(staged, ["a", "b", "c"])
        XCTAssertEqual(rows, [1, 2, 3, 4, 5])
    }

    /// Wie Android: Abbruch mitten im Anlegen → kein Teil-Import (der Stand vorher bleibt).
    func testCancelledImportLeavesPortfolioUnchanged() throws {
        let preview = CsvImportMapping.preview(CsvImportParser.parse(relai(10_000)), existingKeys: [], fx: fx)
        let list = preview.transactions.map(PortfolioCsvImporter.tx)
        let manual = PortfolioTx(id: 7, coin: "ETH", type: .BUY, amount: 1, priceUsdt: 2_000, time: 1)
        let base = PortfolioFile(transactions: [manual], nextId: 8)
        var rows: [Int] = []
        XCTAssertThrowsError(try AppData.withImported(list, batch: "b1", in: base, cancelled: { rows.last == 4_321 },
                                                      onRow: { rows.append($0) })) { error in
            XCTAssertTrue(error is CsvImportCancelled)
        }
        XCTAssertEqual(rows.last, 4_321)
        XCTAssertEqual(base.transactions, [manual], "Daten unverändert")
        XCTAssertEqual(base.nextId, 8)
    }

    /// Ergebnis identisch zum bisherigen Weg (je Zeile `PortfolioStore.insert`), Fortschritt endet bei der Gesamtzahl.
    func testImportedFileEqualsInsertOneByOne() throws {
        let preview = CsvImportMapping.preview(CsvImportParser.parse(relai(1_200)), existingKeys: [], fx: fx)
        var list = preview.transactions.map(PortfolioCsvImporter.tx)
        list.append(list[3]) // doppelt in der Datei: übersprungen
        var existing = list[10]
        existing.id = 40
        let base = PortfolioFile(transactions: [existing], nextId: 12)
        var rows: [Int] = []
        let built = try AppData.withImported(list, batch: "b1", in: base, cancelled: { false }, onRow: { rows.append($0) })

        var file = base
        var known = AppData.importKeys(file.transactions)
        var count = 0
        for tx in list {
            let keys = [tx.importKey, tx.linkedImportKey].compactMap { $0 }
            if keys.contains(where: { known.contains($0) }) { continue }
            known.formUnion(keys)
            var item = tx
            item.id = 0
            item.importBatch = "b1"
            guard let clean = AppData.cleanedPortfolioTx(item) else { continue }
            PortfolioStore.insert(clean, into: &file)
            count += 1
        }
        XCTAssertEqual(built.count, count)
        XCTAssertEqual(built.count, 1_199)
        XCTAssertEqual(built.file.transactions, file.transactions)
        XCTAssertEqual(built.file.nextId, file.nextId)
        XCTAssertEqual(rows.last, list.count)
    }

    // MARK: Vorschau

    func testPreviewWithProgressEqualsPreviewWithoutAndEndsAtTotal() {
        let result = CsvImportParser.parse(relai(1_050))
        let plain = CsvImportMapping.preview(result, existingKeys: [], fx: fx)
        var reported: [(done: Int, total: Int)] = []
        let withProgress = CsvImportMapping.preview(result, existingKeys: [], onRow: { reported.append(($0, $1)) }, fx: fx)
        XCTAssertEqual(withProgress.transactions, plain.transactions)
        XCTAssertEqual(withProgress.missingFx, plain.missingFx)
        XCTAssertEqual(withProgress.missingPrice, plain.missingPrice)
        XCTAssertEqual(plain.transactions.count, 1_050)
        XCTAssertEqual(reported.map(\.done), [100, 200, 300, 400, 500, 600, 700, 800, 900, 1_000, 1_050])
        XCTAssertEqual(reported.last?.total, 1_050)
    }

    // MARK: Hintergrund und Anzeige

    /// Bricht der Aufrufer ab, merkt es auch die losgelöste Arbeit (`Task.isCancelled`) — sie läuft nicht weiter.
    func testBackgroundWorkIsCancelledWithCaller() async {
        let started = expectation(description: "gestartet")
        let caller = Task { () -> Bool in
            await CsvImportBackground.run { () -> Bool in
                started.fulfill()
                for _ in 0..<2_000 {
                    if Task.isCancelled { return true }
                    try? await Task.sleep(nanoseconds: 5_000_000)
                }
                return false
            }
        }
        await fulfillment(of: [started], timeout: 5)
        caller.cancel()
        let sawCancel = await caller.value
        XCTAssertTrue(sawCancel)
    }

    /// Fortschritt kommt auf dem MainActor an; ältere bzw. späte Meldungen (nach dem Ende) zählen nicht.
    func testRunAppliesNewestProgressAndIgnoresLateReports() async throws {
        let run = CsvImportRun()
        let report = run.begin(.saving)
        XCTAssertEqual(run.phase, .saving)
        XCTAssertNil(run.progress)
        let p200 = CsvImportProgress(step: .SAVE, done: 200, total: 1_000)
        report(p200)
        try await waitUntil { run.progress == p200 }
        report(CsvImportProgress(step: .SAVE, done: 100, total: 1_000))
        report(CsvImportProgress(step: .SAVE, done: 1_000, total: 1_000))
        try await waitUntil { run.progress?.done == 1_000 }
        run.end()
        report(CsvImportProgress(step: .SAVE, done: 1_000, total: 1_000))
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertNil(run.phase)
        XCTAssertNil(run.progress, "späte Meldung nach dem Ende zählt nicht")
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<200 where !condition() {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTAssertTrue(condition())
    }
}
