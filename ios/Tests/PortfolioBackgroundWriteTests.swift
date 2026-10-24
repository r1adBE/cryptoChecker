import XCTest
@testable import CryptoChecker

/// `AppData.setPortfolioFileInBackground` auf dem Speicher der Test-App (`AppData.shared`): schreibt nur, wenn seit der
/// genannten Nummer niemand geschrieben hat, prüft die Nummer nach dem Schreiben noch einmal und übernimmt sonst den
/// neuen Stand (`portfolioFile`, `portfolio` neueste zuerst). Der Stand davor wird am Ende wieder geschrieben.
/// Schreibfehler (gesperrte bzw. nicht lesbare Datei) deckt `PortfolioImportSaveTests` mit dem Fake-Speicher ab.
@MainActor
final class PortfolioBackgroundWriteTests: XCTestCase {
    private var original: PortfolioFile?
    /// Der Test hat `portfolio.json` womöglich geändert: am Ende `original` zurückschreiben.
    private var wrote = false

    override func setUp() async throws {
        try await HostStorageKey.prepareAppStorage()
        let data = AppData.shared
        try XCTSkipUnless(data.portfolioIntact, "Portfolio der Test-App nicht lesbar")
        original = data.currentPortfolioFile()
        wrote = false
    }

    override func tearDown() async throws {
        if wrote, let original { _ = AppData.shared.setPortfolioFile(original) }
        original = nil
    }

    /// `file` samt einer neuen Test-Transaktion (nächste freie Id).
    private func withExtra(_ file: PortfolioFile) -> PortfolioFile {
        var next = file
        let id = max(file.nextId, (file.transactions.map(\.id).max() ?? 0) + 1)
        next.transactions.append(PortfolioTx(id: id, coin: "ZZTEST", type: .BUY, amount: 1, priceUsdt: 1,
                                             time: 1_700_000_000_000))
        next.nextId = id + 1
        return next
    }

    /// Nummer veraltet (inzwischen geschrieben): nichts geschrieben, Stand bleibt.
    func testStaleGenerationWritesNothing() async {
        let data = AppData.shared
        let before = data.portfolio
        let generation = AppData.portfolioWrites.current
        let result = await data.setPortfolioFileInBackground(withExtra(data.currentPortfolioFile()),
                                                             generation: generation - 1)
        XCTAssertEqual(result, .superseded)
        XCTAssertEqual(AppData.portfolioWrites.current, generation, "kein Schreiben")
        XCTAssertEqual(data.portfolio, before, "Stand nicht übernommen")
    }

    /// Aktuelle Nummer: geschrieben und übernommen (neueste zuerst), Nummer um eins weiter.
    func testCurrentGenerationWritesAndAdoptsState() async throws {
        let data = AppData.shared
        let next = withExtra(data.currentPortfolioFile())
        let generation = AppData.portfolioWrites.current
        wrote = true
        let result = await data.setPortfolioFileInBackground(next, generation: generation)
        try XCTSkipIf(result == .failed && data.portfolioWriteLocked, "Speicher der Test-App gesperrt")
        XCTAssertEqual(result, .saved)
        XCTAssertEqual(AppData.portfolioWrites.current, generation + 1)
        XCTAssertEqual(data.portfolioFile.transactions, next.transactions)
        XCTAssertEqual(data.portfolioFile.nextId, next.nextId)
        XCTAssertEqual(data.portfolio, PortfolioStore.newestFirst(next.transactions))
    }

    /// Ein anderer schreibt NACH dem Schreiben im Hintergrund, bevor der MainActor den Stand übernimmt: `.superseded`,
    /// der Stand im Speicher bleibt (dessen Stand enthielte den Import nicht).
    func testWriteAfterBackgroundWriteSupersedesIt() async {
        let data = AppData.shared
        let before = data.portfolio
        let next = withExtra(data.currentPortfolioFile())
        let generation = AppData.portfolioWrites.current
        wrote = true
        let pending = Task { await data.setPortfolioFileInBackground(next, generation: generation) }
        // Die Aufgabe bis zum losgelösten Schreiben laufen lassen, dann den MainActor festhalten, bis geschrieben ist —
        // so kann sie den Stand noch nicht übernehmen
        await Task.yield()
        let deadline = Date().addingTimeInterval(5)
        while AppData.portfolioWrites.current == generation && Date() < deadline { usleep(1_000) }
        // Ein synchroner Schreibweg kommt dazwischen (hier nur die Nummer)
        AppData.portfolioWrites.write {}
        let result = await pending.value
        XCTAssertEqual(result, .superseded)
        XCTAssertEqual(data.portfolio, before, "Stand nicht übernommen")
    }
}
