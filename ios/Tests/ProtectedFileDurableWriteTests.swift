import CryptoKit
import XCTest
@testable import CryptoChecker

/// Versiegeltes Schreiben über eine Nachbardatei (F_FULLFSYNC vor `rename`) und der Widget-Stand mit
/// `requireSealed`: ohne lesbaren Schlüssel nie Klartext, der letzte versiegelte Stand bleibt.
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
