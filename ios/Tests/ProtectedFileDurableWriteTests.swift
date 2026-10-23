import CryptoKit
import XCTest
@testable import CryptoChecker

/// Versiegeltes Schreiben über eine Nachbardatei (F_FULLFSYNC bzw. für Kurse F_BARRIERFSYNC vor `rename`) und der
/// Widget-Stand mit `requireSealed`: ohne lesbaren Schlüssel nie Klartext, der letzte versiegelte Stand bleibt.
final class ProtectedFileDurableWriteTests: XCTestCase {
    private var dir: URL!
    private let key = SymmetricKey(data: Data(repeating: 9, count: 32))

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("durable-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func keychain(_ key: SymmetricKey?) -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = key?.withUnsafeBytes { Data($0) }
        return StorageKey(keychain: fake)
    }

    func testVersiegeltGeschriebenOhneUebrigeNachbardatei() throws {
        let url = dir.appendingPathComponent("portfolio_widget.json")
        let first = Data(#"{"total":1}"#.utf8)
        let second = Data(#"{"total":2}"#.utf8)
        try ProtectedFile.writeSealed(first, to: url, key: key)
        try ProtectedFile.writeSealed(second, to: url, key: key)
        XCTAssertEqual(try ProtectedFile.read(url, keys: keychain(key)), second)
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        XCTAssertEqual(names, ["portfolio_widget.json"], "keine .tmp-Datei darf liegen bleiben")
    }

    /// Reine Kurs-Speicherung (Live-Takt) mit F_BARRIERFSYNC und ohne Nachlesen, sonst F_FULLFSYNC mit
    /// Nachlesen — beide versiegelt, lesbar und ohne liegen gebliebene Nachbardatei.
    func testKursSpeicherungMitBarriereStrukturVoll() throws {
        XCTAssertEqual(ProtectedFile.Durability.full.syncCommand, F_FULLFSYNC)
        XCTAssertEqual(ProtectedFile.Durability.barrier.syncCommand, F_BARRIERFSYNC)
        XCTAssertTrue(ProtectedFile.Durability.full.verifiesReadBack)
        XCTAssertFalse(ProtectedFile.Durability.barrier.verifiesReadBack)

        let url = dir.appendingPathComponent("watchlist.json")
        let structure = Data(#"{"watches":[{"id":1}]}"#.utf8)
        let prices = Data(#"{"watches":[{"id":1,"lastPrice":2}]}"#.utf8)
        try ProtectedFile.write(structure, to: url, keys: keychain(key), requireSealed: true)
        try ProtectedFile.write(prices, to: url, keys: keychain(key), requireSealed: true, durability: .barrier)
        XCTAssertTrue(ProtectedStorage.isSealedFile(url))
        XCTAssertEqual(try ProtectedFile.read(url, keys: keychain(key)), prices)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["watchlist.json"])
    }

    /// Merkliste mit Barriere über `writeFiles`: gleicher Inhalt wie voll geschrieben; die Portfolio-Alarme bleiben voll.
    func testMerklisteMitBarriereLesbar() throws {
        let watchlist = dir.appendingPathComponent("watchlist.json")
        let alarms = dir.appendingPathComponent("portfolio_alarms.json")
        var snap = SharedStorage.Snapshot()
        snap.watches = [Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")]
        snap.watches[0].lastPrice = 70_000
        XCTAssertTrue(SharedStorage.writeFiles(snap, watchlist: watchlist, portfolioAlarms: alarms,
                                               includePortfolioInBackup: true, keys: keychain(key), durability: .barrier))
        let read = try XCTUnwrap(SharedStorage.readFiles(watchlist: watchlist, portfolioAlarms: alarms,
                                                         keys: keychain(key)).snapshot)
        XCTAssertEqual(read.watches.first?.lastPrice, 70_000)
        SharedStorage.watchlistPending = false
    }

    func testOhneSchluesselBleibtLetzterVersiegelterStand() throws {
        let url = dir.appendingPathComponent("portfolio_widget.json")
        let stored = Data(#"{"total":1}"#.utf8)
        try ProtectedFile.write(stored, to: url, keys: keychain(key), requireSealed: true)
        let sealedBytes = try Data(contentsOf: url)
        XCTAssertThrowsError(try ProtectedFile.write(Data(#"{"total":2}"#.utf8), to: url, keys: keychain(nil),
                                                     requireSealed: true))
        XCTAssertEqual(try Data(contentsOf: url), sealedBytes)
        XCTAssertEqual(try ProtectedFile.read(url, keys: keychain(key)), stored)
    }
}
